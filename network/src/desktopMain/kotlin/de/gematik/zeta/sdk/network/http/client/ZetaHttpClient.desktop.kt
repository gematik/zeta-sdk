/*
 * #%L
 * ZETA-Client
 * %%
 * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.config.ClientConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyType
import de.gematik.zeta.sdk.network.http.client.config.SecurityConfig
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCertInfo
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCertificateValidator
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCipherSuites
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaSignatureAlgorithms
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsCurves
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsValidator
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.curl.Curl
import io.ktor.client.engine.curl.CurlClientEngineConfig
import io.ktor.client.engine.curl.SslVersion
import io.ktor.client.engine.curl.internal.PendingRevocationData
import io.ktor.client.engine.curl.internal.globalRevocationFn
import io.ktor.client.engine.curl.internal.zetaRevocationFn
import io.ktor.client.engine.curl.tls.ChainCertMeta
import io.ktor.client.engine.curl.tls.LeafCertInfo
import io.ktor.client.engine.curl.tls.TlsSessionData
import io.ktor.client.engine.curl.tls.TlsValidationConfig
import io.ktor.client.engine.curl.zeta_register_revocation_fn
import io.ktor.client.engine.http
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalForeignApi::class)
private fun initZetaRevocationCallback(
    revocationFn: (staple: ByteArray?, certDer: ByteArray, issuerDer: ByteArray) -> Boolean,
) {
    Log.d { "[ZETA-TLS] initZetaRevocationCallback: registering" }

    globalRevocationFn = revocationFn
    zeta_register_revocation_fn(staticCFunction(::zetaRevocationFn))

    Log.d { "[ZETA-TLS] initZetaRevocationCallback: done" }
}

private fun validateRevocationBlocking(
    staple: ByteArray?,
    certDer: ByteArray,
    issuerDer: ByteArray,
    revocationChecker: RevocationChecker?,
): Boolean = runCatching {
    Log.d { "[ZETA-TLS] validateRevocationBlocking: staple=${staple?.size} certDer=${certDer.size} issuerDer=${issuerDer.size}" }
    runBlocking {
        val checker = requireNotNull(revocationChecker) {
            "RevocationChecker is required when TLS validation is enabled"
        }
        checker.validate(
            stapledOcspResponse = staple,
            certDer = certDer,
            issuerDer = issuerDer,
        )
    }
}.onFailure {
    Log.e { "[ZETA-TLS] validateRevocationBlocking FAILED: ${it.message}" }
}.isSuccess

internal actual fun buildPlatformClient(
    cfg: ClientConfig,
    dependencies: HttpClientDependencies,
    commonSetup: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    Log.d { "[ZETA-TLS] buildPlatformClient: registering revocation callback" }

    initZetaRevocationCallback { staple, certDer, issuerDer ->
        validateRevocationBlocking(staple, certDer, issuerDer, dependencies.revocationChecker)
    }

    Log.d { "[ZETA-TLS] buildPlatformClient: building curl client" }

    return HttpClient(Curl) {
        engine {
            applyTlsConfig(cfg.security, dependencies)
            applyProxyConfig(cfg.network.proxyConfig)
        }
        install(WebSockets)
        commonSetup(this)
    }
}

private fun CurlClientEngineConfig.applyTlsConfig(security: SecurityConfig, dependencies: HttpClientDependencies) {
    Log.d { "[ZETA-TLS] applyTlsConfig: disableServerValidation=${security.disableServerValidation} additionalCaPem=${security.additionalCaPem.size} items additionalCaFile=${security.additionalCaFile}" }

    sslVerify = !security.disableServerValidation
    sslVerifyStatus = false
    security.additionalCaFile?.let { caInfo = it }

    val explicitPemBlob = security.additionalCaPem
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n")
        ?.toByteArray(Charsets.UTF_8)

    val pemBlob = when {
        explicitPemBlob != null -> explicitPemBlob
        security.additionalCaFile != null -> null
        else -> platformDefaultCaBundlePem()
    }

    if (pemBlob != null) {
        Log.d { "[ZETA-TLS] applyTlsConfig: caPemBlob ${pemBlob.size} bytes" }
        caPemBlob = pemBlob
    } else {
        Log.d { "[ZETA-TLS] applyTlsConfig: no caPem to apply" }
    }

    sslVerbose = security.sslVerbose

    if (!security.disableServerValidation) {
        sslVersion = SslVersion.TLS_1_2_TO_1_3
        sslCipherList = ZetaCipherSuites.FULL_PREFERRED_ORDER.joinToString(":")
        tls13Ciphers = ZetaCipherSuites.TLS_1_3_SUITES.joinToString(":")
        sslEcCurves = ZetaTlsCurves.ALLOWED.joinToString(":")
        sslSignatureAlgorithms = ZetaSignatureAlgorithms.ALLOWED.joinToString(":")
        tlsValidationConfig = TlsValidationConfig(
            onSessionValidated = { sessionData ->
                validateSession(
                    sessionData = sessionData,
                    clock = dependencies.clock,
                )
            },
        )
    }
}

internal fun validateSession(sessionData: TlsSessionData, clock: ZetaClock): PendingRevocationData? {
    Log.d { "[ZETA-TLS] validateSession: host=${sessionData.host} protocol=${sessionData.protocol} cipher=${sessionData.cipherSuite}" }

    val leafCertInfo = sessionData.leafCertInfo ?: run {
        Log.e { "[ZETA-TLS] validateSession: NO leaf cert, rejecting" }
        error("TLS compliance failure: leaf certificate missing")
    }

    Log.d { "[ZETA-TLS] validateSession: subject=${leafCertInfo.subjectDN} sigAlg=${leafCertInfo.sigAlgSn} keyType=${leafCertInfo.keyTypeName} keyBits=${leafCertInfo.keyBits} curve=${leafCertInfo.curveName} san=${leafCertInfo.san}" }

    val tlsResult = ZetaTlsValidator.validateHandshake(
        negotiatedCipher = sessionData.cipherSuite!!,
        negotiatedProtocol = sessionData.protocol!!,
        negotiatedCurve = leafCertInfo.curveName,
    )
    Log.d { "[ZETA-TLS] tlsResult: compliant=${tlsResult.isCompliant} errors=${tlsResult.errors} warnings=${tlsResult.warnings}" }

    val certResult = leafCertInfo.fullChain?.let { chain ->
        ZetaCertificateValidator.validateChain(
            chain.map { it.toZetaCertInfo() },
            clock.now().epochSeconds,
            sessionData.host,
        )
    } ?: ZetaCertificateValidator.validate(leafCertInfo.toZetaCertInfo(), clock.now().epochSeconds, sessionData.host)

    Log.d { "[ZETA-TLS] certResult: valid=${certResult.isValid} errors=${certResult.errors}" }

    val errors = tlsResult.errors + certResult.errors
    val isCompliant = tlsResult.isCompliant && certResult.isValid
    if (!isCompliant) {
        Log.d { "[ZETA-TLS] validateSession: NON-COMPLIANT errors=$errors" }
        error("TLS compliance failure: $errors")
    }

    tlsResult.warnings.forEach { Log.w { "ZetaTls: $it" } }
    Log.d { "[ZETA-TLS] validateSession: compliant" }

    return null
}

private fun CurlClientEngineConfig.applyProxyConfig(proxyConfig: ProxyConfig?) {
    if (proxyConfig == null) {
        Log.i { "[SDK-PROXY] No proxy configured" }
        return
    }
    Log.i { "[SDK-PROXY] Configuring proxy: ${proxyConfig.type} ${proxyConfig.host}:${proxyConfig.port}" }
    proxy = when (proxyConfig.type) {
        ProxyType.HTTP -> {
            val credentials = if (proxyConfig.username != null && proxyConfig.password != null) {
                "${proxyConfig.username}:${proxyConfig.password.concatToString()}@"
            } else {
                ""
            }
            val url = "http://$credentials${proxyConfig.host}:${proxyConfig.port}"
            Log.i { "[SDK-PROXY] Setting HTTP proxy: http://${proxyConfig.host}:${proxyConfig.port}" }
            ProxyBuilder.http(url)
        }
        ProxyType.SOCKS -> {
            val credentials = if (proxyConfig.username != null && proxyConfig.password != null) {
                "${proxyConfig.username}:${proxyConfig.password.concatToString()}@"
            } else {
                ""
            }
            val url = "socks5://$credentials${proxyConfig.host}:${proxyConfig.port}"
            Log.i { "[SDK-PROXY] Setting SOCKS proxy: $url" }
            ProxyBuilder.http(url)
        }
    }
    Log.i { "[SDK-PROXY] Proxy configured successfully" }
}

internal fun LeafCertInfo.toZetaCertInfo() = ZetaCertInfo(
    subjectDN = subjectDN ?: "",
    sigAlgName = sigAlgSn?.toCanonicalSigAlgName() ?: "",
    keyAlgorithm = keyTypeName ?: "",
    keySize = keyBits ?: 0,
    curveName = curveName,
    notBefore = notBefore ?: error("Missing notBefore in leaf cert - rejecting"),
    notAfter = notAfter ?: Long.MAX_VALUE,
    san = san ?: error("Missing SAN in leaf cert - rejecting"),
)

private fun String.toCanonicalSigAlgName(): String = when (
    uppercase()
        .replace("-", "")
        .replace("_", "")
        .replace("/", "")
        .replace(" ", "")
) {
    "ECDSASHA256", "ECDSAWITHSHA256", "SHA256WITHECDSA" -> "SHA256WITHECDSA"
    "ECDSASHA384", "ECDSAWITHSHA384", "SHA384WITHECDSA" -> "SHA384WITHECDSA"
    "ECDSASHA512", "ECDSAWITHSHA512", "SHA512WITHECDSA" -> "SHA512WITHECDSA"
    "RSASHA256", "RSAWITHSHA256", "SHA256WITHRSA" -> "SHA256WITHRSA"
    "RSASHA384", "RSAWITHSHA384", "SHA384WITHRSA" -> "SHA384WITHRSA"
    "RSASHA512", "RSAWITHSHA512", "SHA512WITHRSA" -> "SHA512WITHRSA"
    else -> this
}

private fun ChainCertMeta.toZetaCertInfo() = ZetaCertInfo(
    subjectDN = subjectDN ?: "",
    sigAlgName = sigAlgSn?.toCanonicalSigAlgName() ?: "",
    keyAlgorithm = keyTypeName ?: "",
    keySize = keyBits ?: 0,
    curveName = curveName,
    notBefore = notBefore ?: 0L,
    notAfter = notAfter ?: Long.MAX_VALUE,
    san = san ?: emptyList(),
)

internal expect fun platformDefaultCaBundlePem(): ByteArray?
