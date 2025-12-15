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
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
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
    val socketFactory: SSLSocketFactory
    val trustManager: X509TrustManager

    val disableTLSVerification = cfg.security.disableServerValidation

    Log.i { "JVM: Disable server validation = $disableTLSVerification" }

    if (disableTLSVerification) {
        val allTrustedCerts = arrayOf<TrustManager>(object : X509TrustManager {
            @Suppress("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate?>?, authType: String?) { // NOSONAR
                // do nothing - override check for test environments
            }

            @Suppress("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate?>?, authType: String?) { // NOSONAR
                // do nothing - override check for test environments
            }
            override fun getAcceptedIssuers(): Array<out X509Certificate?> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, allTrustedCerts, SecureRandom())

        socketFactory = sslContext.socketFactory
        trustManager = allTrustedCerts[0] as X509TrustManager
    } else {
        // Parse additional CA PEMs to X.509 certificates.
        val certFactory = CertificateFactory.getInstance("X.509")

        // Each string is expected to be a full PEM including BEGIN/END delimiters.
        val extraCerts: List<X509Certificate> = cfg.security.additionalCaPem.map { pem ->
            certFactory.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
        }

        // Build a trust manager that combines platform CAs with the extra CAs.
        val handshakeCerts = HandshakeCertificates.Builder()
            .addPlatformTrustedCertificates()
            .apply { extraCerts.forEach { addTrustedCertificate(it) } }
            .build()

        socketFactory = handshakeCerts.sslSocketFactory()
        trustManager = handshakeCerts.trustManager
    }

    // Preconfigure OkHttp with the custom trust manager + SSLSocketFactory.
    val okClient = OkHttpClient.Builder()
        .hostnameVerifier { _, _ -> true } // NOSONAR
        .sslSocketFactory(socketFactory, trustManager)
        .build()

    // Create the Ktor client with OkHttp engine, applying shared setup and the preconfigured client.
    return HttpClient(OkHttp) {
        this.apply {
            commonSetup(this)
            engine {
                preconfigured = okClient
            }
            install(WebSockets) {
                pingInterval = 30.seconds
            }
        }
    }
}
