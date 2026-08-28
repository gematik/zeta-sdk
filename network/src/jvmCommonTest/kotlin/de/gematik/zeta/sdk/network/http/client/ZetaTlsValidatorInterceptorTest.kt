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
import okhttp3.CipherSuite
import okhttp3.Connection
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class ZetaTlsValidatorInterceptorTest {

    private val interceptor = ZetaTlsValidatorInterceptor()

    @Test
    fun intercept_proceeds_whenHandshakeIsNull() {
        val expectedResponse = response()
        val chain = FakeChain(
            connection = null,
            response = expectedResponse,
        )

        val result = interceptor.intercept(chain)

        assertSame(expectedResponse, result)
        assertEquals(1, chain.proceedCalls)
    }

    @Test
    fun intercept_proceeds_whenHandshakeIsCompliant() {
        val expectedResponse = response()
        val chain = FakeChain(
            connection = FakeConnection(
                handshake = handshake(
                    cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256,
                    tlsVersion = TlsVersion.TLS_1_3,
                ),
            ),
            response = expectedResponse,
        )

        val result = interceptor.intercept(chain)

        assertSame(expectedResponse, result)
        assertEquals(1, chain.proceedCalls)
    }

    @Test
    fun intercept_throws_whenHandshakeIsNonCompliant() {
        val chain = FakeChain(
            connection = FakeConnection(
                handshake = handshake(
                    cipherSuite = CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
                    tlsVersion = TlsVersion.TLS_1_2,
                ),
            ),
            response = response(),
        )

        val error = assertFailsWith<SSLException> {
            interceptor.intercept(chain)
        }

        assertTrue(error.message!!.contains("gematik TLS compliance failure"))
        assertEquals(0, chain.proceedCalls)
    }

    private fun handshake(
        cipherSuite: CipherSuite,
        tlsVersion: TlsVersion,
    ): Handshake {
        val session = mockk<SSLSession> {
            every { getCipherSuite() } returns cipherSuite.javaName
            every { getProtocol() } returns tlsVersion.javaName
            every { getPeerHost() } returns "example.com"
            every { getPeerPort() } returns 443
            every { getPeerCertificates() } returns emptyArray()
            every { getLocalCertificates() } returns emptyArray()
        }
        return session.handshake()
    }

    private fun response(): Response =
        Response.Builder()
            .request(
                Request.Builder()
                    .url("https://example.com")
                    .build(),
            )
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

    private class FakeChain(
        private val connection: Connection?,
        private val response: Response,
    ) : Interceptor.Chain {

        var proceedCalls: Int = 0
            private set

        private val request = Request.Builder()
            .url("https://example.com")
            .build()

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceedCalls++
            return response
        }

        override fun connection(): Connection? = connection

        override fun call() = throw UnsupportedOperationException("Not needed in this test")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private class FakeConnection(
        private val handshake: Handshake?,
    ) : Connection {
        override fun route() = throw UnsupportedOperationException("Not needed in this test")
        override fun socket() = throw UnsupportedOperationException("Not needed in this test")
        override fun handshake(): Handshake? = handshake
        override fun protocol(): Protocol = Protocol.HTTP_1_1
    }
}
