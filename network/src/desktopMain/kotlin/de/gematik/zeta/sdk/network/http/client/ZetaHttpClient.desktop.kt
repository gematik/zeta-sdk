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
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.curl.Curl
import io.ktor.client.engine.curl.CurlClientEngineConfig
import io.ktor.client.engine.curl.SslVersion
import io.ktor.client.engine.curl.tls.LeafCertInfo
import io.ktor.client.engine.curl.tls.TlsSessionData
import io.ktor.client.engine.curl.tls.TlsValidationConfig
import io.ktor.client.engine.http
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.time.Clock
internal actual fun buildPlatformClient(cfg: ClientConfig, commonSetup: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Curl) {
        engine {
            applyTlsConfig(cfg.security)
            applyProxyConfig(cfg.network.proxyConfig)
        }
        install(WebSockets)
        commonSetup(this)
    }

private fun CurlClientEngineConfig.applyTlsConfig(security: SecurityConfig) {
    sslVerify = !security.disableServerValidation
    security.additionalCaFile?.let { caInfo = it }
    sslVersion = SslVersion.TLS_1_2
    sslCipherList = ZetaCipherSuites.FULL_PREFERRED_ORDER.joinToString(":")
    tls13Ciphers = ZetaCipherSuites.TLS_1_3_SUITES.joinToString(":")
    sslEcCurves = ZetaTlsCurves.ALLOWED.joinToString(":")
    sslSignatureAlgorithms = ZetaSignatureAlgorithms.ALLOWED_WITH_RSA.joinToString(":")

    if (!security.disableServerValidation) {
        tlsValidationConfig = TlsValidationConfig(onSessionValidated = ::validateSession)
    }
}

private fun validateSession(sessionData: TlsSessionData) {
    val tlsResult = ZetaTlsValidator.validateHandshake(
        negotiatedCipher = sessionData.cipherSuite!!,
        negotiatedProtocol = sessionData.protocol!!,
    )
    val certResult = sessionData.leafCertInfo?.let {
        ZetaCertificateValidator.validate(it.toZetaCertInfo(), Clock.System.now().epochSeconds)
    }

    val errors = tlsResult.errors + (certResult?.errors ?: emptyList())
    val isCompliant = tlsResult.isCompliant && (certResult?.isValid != false)

    if (!isCompliant) {
        Log.e { "Handshake NON-COMPLIANT errors=$errors" }
        error("TLS compliance failure: $errors")
    }

    tlsResult.warnings.forEach { Log.w { "ZetaTls: $it" } }
}

private fun CurlClientEngineConfig.applyProxyConfig(proxyConfig: ProxyConfig?) {
    proxyConfig ?: return
    proxy = when (proxyConfig.type) {
        ProxyType.HTTP -> ProxyBuilder.http("http://${proxyConfig.host}:${proxyConfig.port}")

        ProxyType.SOCKS -> {
            val credentials = if (proxyConfig.username != null && proxyConfig.password != null) {
                "${proxyConfig.username}:${proxyConfig.password.concatToString()}@"
            } else {
                ""
            }
            ProxyBuilder.http("socks5://$credentials${proxyConfig.host}:${proxyConfig.port}")
        }
    }
}

internal fun LeafCertInfo.toZetaCertInfo() = ZetaCertInfo(
    subjectDN = subjectDN ?: "",
    sigAlgName = sigAlgSn?.toCanonicalSigAlgName() ?: "",
    keyAlgorithm = keyTypeName ?: "",
    keySize = keyBits ?: 0,
    curveName = curveName,
    notBefore = notBefore ?: 0L,
    notAfter = notAfter ?: Long.MAX_VALUE,
)

private fun String.toCanonicalSigAlgName(): String = when (
    uppercase()
        .replace("-", "")
        .replace("_", "")
        .replace("/", "")
        .replace(" ", "")
) {
    "RSASHA256", "SHA256WITHRSA", "SHA256WITHRSAENCRYPTION" -> "SHA256WITHRSA"

    "RSASHA384", "SHA384WITHRSA", "SHA384WITHRSAENCRYPTION" -> "SHA384WITHRSA"

    "RSASHA512", "SHA512WITHRSA", "SHA512WITHRSAENCRYPTION" -> "SHA512WITHRSA"

    "ECDSASHA256", "ECDSAWITHSHA256", "SHA256WITHECDSA" -> "SHA256WITHECDSA"

    "ECDSASHA384", "ECDSAWITHSHA384", "SHA384WITHECDSA" -> "SHA384WITHECDSA"

    "ECDSASHA512", "ECDSAWITHSHA512", "SHA512WITHECDSA" -> "SHA512WITHECDSA"

    "RSASSAPSS", "SHA256WITHRSAANDMGF1", "SHA256WITHRSSPSS",
    "SHA256WITHRSAPSS", "RSAPSS",
    -> "SHA256WITHRSAANDMGF1"

    else -> this
}
