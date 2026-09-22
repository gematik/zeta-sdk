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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CipherSuite
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class ZetaTlsValidatorInterceptorTest {
    private val interceptor = ZetaTlsValidatorInterceptor()
    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates

    @BeforeTest
    fun setUp() {
        val serverCert = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCert.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun intercept_proceeds_whenHandshakeIsCompliant() {
        server.enqueue(MockResponse(body = "ok"))

        val compliantSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_3)
            .cipherSuites(CipherSuite.TLS_AES_128_GCM_SHA256)
            .build()

        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .connectionSpecs(listOf(compliantSpec))
            .addInterceptor(interceptor)
            .build()

        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun intercept_proceeds_whenConnectionIsNull() {
        val chain = mockk<Interceptor.Chain>(relaxed = true) {
            every { connection() } returns null
        }

        interceptor.intercept(chain)

        verify(exactly = 1) { chain.proceed(any()) }
    }

    @Test
    fun intercept_throws_whenHandshakeIsNonCompliant() {
        val connection = mockk<Connection> {
            every { handshake() } returns nonCompliantHandshake()
        }
        val chain = mockk<Interceptor.Chain>(relaxed = true) {
            every { connection() } returns connection
        }

        val error = assertFailsWith<SSLException> {
            interceptor.intercept(chain)
        }

        assertTrue(error.message!!.contains("gematik TLS compliance failure"))
        verify(exactly = 0) { chain.proceed(any()) }
    }

    private fun nonCompliantHandshake(): Handshake {
        val session = mockk<SSLSession> {
            every { getCipherSuite() } returns CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA.javaName
            every { getProtocol() } returns TlsVersion.TLS_1_2.javaName
            every { getPeerHost() } returns "example.com"
            every { getPeerPort() } returns 443
            every { getPeerCertificates() } returns emptyArray()
            every { getLocalCertificates() } returns emptyArray()
        }
        return session.handshake()
    }
}
