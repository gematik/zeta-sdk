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
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCipherSuites
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTrustManager
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds

/**
 * JVM/Android actual that builds an OkHttp-backed [HttpClient].
 *
 * Responsibilities:
 * 1. Parse additional CA certificates from [cfg.security.additionalCaPem] (PEM strings),
 *    and append them to the platform trust store via OkHttp's [HandshakeCertificates].
 * 2. Create a preconfigured [OkHttpClient] that uses the resulting SSL context +
 *    trust manager.
 * 3. Build a Ktor [HttpClient] with the OkHttp engine, applying the shared [commonSetup].
 *
 * Security notes:
 * - Extra CAs are trusted for **server authentication** only (no mutual TLS/client certs here).
 * - Each entry in [cfg.security.additionalCaPem] must be a **complete PEM** block, including
 *   the delimiters:
 *
 *     -----BEGIN CERTIFICATE-----
 *     (base64)
 *     -----END CERTIFICATE-----
 *
 * - Invalid PEMs will cause a [java.security.cert.CertificateException] at parse time.
 *
 * Lifecycle:
 * - The provided OkHttpClient instance is passed to Ktor as `preconfigured`. If you reuse
 *   that instance elsewhere, be mindful of its dispatcher/connection-pool lifecycle.
 *
 * @param cfg Finalized client configuration (timeouts, retries, security, etc.).
 * @param commonSetup Cross-platform Ktor configuration to apply to the client (plugins, JSON, …).
 * @return A ready-to-use Ktor [HttpClient] using OkHttp on JVM/Android.
 */
internal actual fun buildPlatformClient(
    cfg: ClientConfig,
    commonSetup: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val serverValidationDisabled = cfg.security.disableServerValidation
    Log.i { "JVM: Disable server validation = $serverValidationDisabled" }

    val (socketFactory, trustManager) = buildTlsComponents(cfg.security)

    val okClient = OkHttpClient.Builder()
        .applyHostnameVerifier(serverValidationDisabled)
        .sslSocketFactory(socketFactory, trustManager)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = cfg.network.maxRequest
                maxRequestsPerHost = cfg.network.maxRequest
            },
        )
        .connectionSpecs(buildConnectionSpecs(serverValidationDisabled))
        .apply {
            if (!serverValidationDisabled) {
                addNetworkInterceptor(ZetaTlsValidatorInterceptor())
            }
        }
        .applyProxy(cfg.network.proxyConfig)
        .build()

    return HttpClient(OkHttp) {
        engine { preconfigured = okClient }
        install(WebSockets) { pingInterval = 30.seconds }
        commonSetup(this)
    }
}

private fun buildTlsComponents(security: SecurityConfig): Pair<SSLSocketFactory, X509TrustManager> =
    if (security.disableServerValidation) {
        buildInsecureTls()
    } else {
        buildSecureTls(security)
    }

private fun buildInsecureTls(): Pair<SSLSocketFactory, X509TrustManager> {
    Log.w { "JVM: TLS validation DISABLED — not compliant with gematik TLS requirements" }
    val trustAll = TrustAllX509TrustManager()
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }
    return sslContext.socketFactory to trustAll
}

private fun buildSecureTls(securityConfig: SecurityConfig): Pair<SSLSocketFactory, X509TrustManager> {
    val certFactory = CertificateFactory.getInstance("X.509")
    val extraCerts = buildList {
        securityConfig.additionalCaPem.forEach { pem ->
            certFactory.generateCertificates(ByteArrayInputStream(pem.toByteArray()))
                .forEach { add(it as X509Certificate) }
        }
        securityConfig.additionalCaFile?.let { path ->
            File(path).inputStream().use { input ->
                certFactory.generateCertificates(input)
                    .forEach { add(it as X509Certificate) }
            }
        }
    }

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    defaultTmf.init(null as KeyStore?)
    (defaultTmf.trustManagers.first() as X509TrustManager)
        .acceptedIssuers.forEachIndexed { i, cert -> keyStore.setCertificateEntry("platform-$i", cert) }

    extraCerts.forEachIndexed { i, cert -> keyStore.setCertificateEntry("extra-$i", cert) }

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }

    return ZetaSslSocketFactory(sslContext.socketFactory) to ZetaTrustManager(trustManager)
}

@Suppress("SpreadOperator")
private fun buildConnectionSpecs(disableTLSVerification: Boolean): List<ConnectionSpec> =
    if (disableTLSVerification) {
        listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS)
    } else {
        val cipherSuites = ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA
            .mapNotNull { runCatching { CipherSuite.forJavaName(it) }.getOrNull() }
            .toTypedArray()
        listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .cipherSuites(*cipherSuites) // NOSONAR required by OkHttp API
                .build(),
        )
    }

private fun OkHttpClient.Builder.applyProxy(proxyConfig: ProxyConfig?): OkHttpClient.Builder {
    proxyConfig ?: return this
    val proxyType = when (proxyConfig.type) {
        ProxyType.HTTP -> Proxy.Type.HTTP
        ProxyType.SOCKS -> Proxy.Type.SOCKS
    }
    proxy(Proxy(proxyType, InetSocketAddress(proxyConfig.host, proxyConfig.port)))
    if (proxyConfig.type == ProxyType.SOCKS &&
        proxyConfig.username != null &&
        proxyConfig.password != null
    ) {
        System.setProperty("java.net.socks.username", proxyConfig.username)
        System.setProperty("java.net.socks.password", proxyConfig.password.concatToString())
    }
    return this
}

private fun OkHttpClient.Builder.applyHostnameVerifier(
    disableTLSVerification: Boolean,
): OkHttpClient.Builder {
    if (disableTLSVerification) {
        Log.w { "JVM: Hostname verification DISABLED — not compliant with gematik requirements" }
        hostnameVerifier { _, _ -> true } // NOSONAR
    }
    return this
}

@Suppress("TrustAllX509TrustManager", "CustomX509TrustManager")
private class TrustAllX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate?>?, authType: String?) {} // NOSONAR
    override fun checkServerTrusted(chain: Array<out X509Certificate?>?, authType: String?) {} // NOSONAR
    override fun getAcceptedIssuers(): Array<out X509Certificate?> = emptyArray()
}
