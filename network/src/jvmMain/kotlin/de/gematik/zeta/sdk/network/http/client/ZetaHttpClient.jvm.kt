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

import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsProtocols.TLS_1_2
import io.ktor.client.HttpClient
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory

/**
 * JVM/Android actual that builds an OkHttp-backed [HttpClient].
 * Lifecycle:
 * - The provided OkHttpClient instance is passed to Ktor as `preconfigured`. If you reuse
 *   that instance elsewhere, be mindful of its dispatcher/connection-pool lifecycle.
 *
 * @param cfg Finalized client configuration (timeouts, retries, security, etc.).
 * @param commonSetup Cross-platform Ktor configuration to apply to the client (plugins, JSON, …).
 * @return A ready-to-use Ktor [HttpClient] using OkHttp on JVM/Android.
 */

internal actual fun loadCaFromFile(
    path: String,
    certFactory: CertificateFactory,
): List<X509Certificate> =
    File(path).inputStream().use { input ->
        certFactory.generateCertificates(input).map { it as X509Certificate }
    }

internal actual fun createPlatformSslSocketFactory(base: SSLSocketFactory): SSLSocketFactory =
    ZetaSslSocketJvmFactory(base)

internal actual fun extractStaple(session: SSLSession): ByteArray? =
    (session as? ExtendedSSLSession)
        ?.statusResponses
        ?.firstOrNull()

internal actual fun createPlatformSslContext(): SSLContext =
    SSLContext.getInstance(TLS_1_2)
