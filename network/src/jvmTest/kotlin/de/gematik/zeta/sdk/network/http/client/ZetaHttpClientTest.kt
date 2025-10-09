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

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import java.net.InetAddress
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * JVM TLS/CA tests for `zetaHttpClient`.
 * Notes:
 * - Uses OkHttp MockWebServer with TLS for real handshakes.
 */
class ZetaHttpClientJvmTest {

    /** Without the server’s CA, TLS handshake fails (platform roots won’t help). */
    // TODO: Ignoring test while TLS verification is ignored
    @Test
    @Ignore
    fun testTlsFailsWithoutCustomRootShowsTrustFailure() = runTest {
        // Arrange
        val (server, _) = startTlsServerWithCustomRoot()
        try {
            val client =
                ZetaHttpClientBuilder(server.url("/").toString())
                    .build()
            // Act
            val ex = assertFails { client.get("/") }

            // Assert
            assertTrue(ex.hasCause<SSLHandshakeException>())
        } finally {
            server.shutdown()
        }
    }

    /** Even if the CA is trusted, a hostname mismatch must fail with peer-unverified. */
    @Test
    fun testTlsFailsOnHostnameMismatchShowsPeerUnverified() = runTest {
        // Arrange: server cert SAN is "bad.local", but client uses "localhost"
        val (server, root) = startTlsServerWithCustomRoot(host = "bad.local")
        try {
            val client =
                ZetaHttpClientBuilder(server.url("/").toString())
                    .addCaPem(root.certificatePem())
                    .build()
            // Act
            val ex = assertFails { client.get("/") }

            // Assert
            assertTrue(ex.hasCause<SSLPeerUnverifiedException>())
        } finally {
            server.shutdown()
        }
    }

    /** Adding the server’s CA enables a successful TLS handshake. */
    @Test
    fun testTlsSucceedsWithCustomRootAdded() = runTest {
        // Arrange
        val (server, root) = startTlsServerWithCustomRoot()
        try {
            val client =
                ZetaHttpClientBuilder(server.url("/").toString())
                    .addCaPem(root.certificatePem())
                    .build()
            // Act
            val res = client.get("/")

            // Assert
            assertEquals(HttpStatusCode.OK, res.status)
        } finally {
            server.shutdown()
        }
    }

    /** With two different custom roots added, both servers are trusted. */
    @Test
    fun testTlsSucceedsForMultipleCustomRoots() = runTest {
        // Arrange
        val (serverA, rootA) = startTlsServerWithCustomRoot()
        val (serverB, rootB) = startTlsServerWithCustomRoot()
        try {
            val client =
                ZetaHttpClientBuilder(serverA.url("/").toString())
                    .addCaPem(rootA.certificatePem())
                    .addCaPem(rootB.certificatePem())
                    .build()

            // Act
            val okA = client.get(serverA.url("/a").toString()).status == HttpStatusCode.OK
            val okB = client.get(serverB.url("/b").toString()).status == HttpStatusCode.OK

            // Assert
            assertTrue(okA && okB)
        } finally {
            serverA.shutdown(); serverB.shutdown()
        }
    }

    // ---- Helpers ------------------------------------------------------------

    /** Start a TLS server signed by a one-off root CA; returns Pair(server, rootCA). */
    private fun startTlsServerWithCustomRoot(host: String = "localhost"): Pair<MockWebServer, HeldCertificate> {
        val root = HeldCertificate.Builder().certificateAuthority(1).build()
        val serverCert = HeldCertificate.Builder()
            .signedBy(root)
            .addSubjectAlternativeName(host).apply {
                // Needed if running tests on Windows
                if (host == "localhost") addSubjectAlternativeName(InetAddress.getByName(host).hostAddress)
            }.build()
        val serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCert, root.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverHandshake.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            start()
        }
        return server to root
    }

    /** Traverse the cause chain and check if it contains a throwable of type [T]. */
    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this as Throwable?) { it.cause }.any { it is T }
}
