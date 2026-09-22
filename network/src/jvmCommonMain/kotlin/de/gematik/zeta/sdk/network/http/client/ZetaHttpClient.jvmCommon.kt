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
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCipherSuites
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
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
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds

/**
 * Load CA certificates from a file path using a platform-specific strategy.
 * JVM reads directly from the filesystem; Android reads from assets.
 */
internal expect fun loadCaFromFile(
    path: String,
    certFactory: CertificateFactory,
): List<X509Certificate>

/**
 * Wrap the base [SSLSocketFactory] in a platform-specific subclass
 */
internal expect fun createPlatformSslSocketFactory(base: SSLSocketFactory): SSLSocketFactory

/**
 * Extract the stapled OCSP response from the TLS [session] using a platform-specific strategy.
 */
internal expect fun extractStaple(session: SSLSession): ByteArray?

/**
 * Create the [SSLContext] that backs the SDK's TLS stack, using a platform-specific provider.
 * Android builds it from a bundled Conscrypt provider so that the stapled OCSP response is
 * readable on every supported API level; the JVM uses the platform default.
 */
internal expect fun createPlatformSslContext(): SSLContext

/**
 * Builds an OkHttp-backed [HttpClient] shared by JVM and Android.
 * Called by each platform's [buildPlatformClient] actual.
 */
internal actual fun buildPlatformClient(
    cfg: ClientConfig,
    dependencies: HttpClientDependencies,
    commonSetup: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val serverValidationDisabled = cfg.security.disableServerValidation
    Log.i { "JVM: Disable server validation = $serverValidationDisabled" }

    if (cfg.security.sslVerbose) {
        System.setProperty("javax.net.debug", "ssl:handshake")
    }

    val (socketFactory, trustManager) = buildTlsComponents(cfg, dependencies)

    val okClient = OkHttpClient.Builder()
        .applyHostnameVerifier(serverValidationDisabled)
        .sslSocketFactory(socketFactory, trustManager)
        .connectionSpecs(buildConnectionSpecs(serverValidationDisabled))
        .apply {
            if (!serverValidationDisabled) {
                addNetworkInterceptor(ZetaTlsValidatorInterceptor())
            }
        }
        .applyProxy(cfg.network.proxyConfig)
        .retryOnConnectionFailure(false)
        .build()

    return HttpClient(OkHttp) {
        engine { preconfigured = okClient }
        install(WebSockets) { pingInterval = 30.seconds }
        commonSetup(this)
    }
}

private fun buildTlsComponents(cfg: ClientConfig, dependencies: HttpClientDependencies): Pair<SSLSocketFactory, X509TrustManager> =
    if (cfg.security.disableServerValidation) {
        buildInsecureTls()
    } else {
        buildSecureTls(cfg, dependencies)
    }

// NOSONAR: intentionally disables certificate validation for local/dev debugging only
internal fun buildInsecureTls(): Pair<SSLSocketFactory, X509TrustManager> {
    Log.w { "JVM: TLS Server Validation: DISABLED" }
    val trustAll = TrustAllX509TrustManager()
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }
    return sslContext.socketFactory to trustAll
}

private fun buildSecureTls(cfg: ClientConfig, dependencies: HttpClientDependencies): Pair<SSLSocketFactory, X509TrustManager> {
    val certFactory = CertificateFactory.getInstance("X.509")
    val extraCerts = buildList {
        cfg.security.additionalCaPem.forEach { pem ->
            certFactory.generateCertificates(ByteArrayInputStream(pem.toByteArray()))
                .forEach { add(it as X509Certificate) }
        }
        cfg.security.additionalCaFile?.let { path ->
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
    val baseTrustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

    val zetaTrustManager = ZetaTrustManager(
        delegate = baseTrustManager,
        revocationChecker = dependencies.revocationChecker,
        clock = dependencies.clock,
    )

    val sslContext = createPlatformSslContext().apply {
        init(null, arrayOf<TrustManager>(zetaTrustManager), SecureRandom())
    }

    val socketFactory = createPlatformSslSocketFactory(sslContext.socketFactory)

    return socketFactory to zetaTrustManager
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

    if (proxyConfig.username != null && proxyConfig.password != null) {
        when (proxyConfig.type) {
            ProxyType.HTTP -> {
                proxyAuthenticator { _, response ->
                    val credential = okhttp3.Credentials.basic(
                        proxyConfig.username,
                        proxyConfig.password.concatToString(),
                    )
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            ProxyType.SOCKS -> {
                System.setProperty("java.net.socks.username", proxyConfig.username)
                System.setProperty("java.net.socks.password", proxyConfig.password.concatToString())
            }
        }
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
