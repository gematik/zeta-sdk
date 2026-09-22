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

import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RevocationClosesConnectionJvmTest {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    @Test
    fun connect_validCertificateNotRevoked_requestReachesServer() = runTest {
        val ca = generateCa()
        val leaf = generateLeafCert(ca, san = "127.0.0.1")
        withTestServer(leaf, listOf(ca.cert)) { server ->
            val sdkClient = buildClient(server.port, trustedCa = ca, checker = passingRevocationChecker())

            val response = sdkClient.get("/")

            assertEquals(200, response.status.value)
            val requestLine = server.awaitReceivedBytes(timeoutMs = 3_000).decodeToString()
            assertTrue(requestLine.startsWith("GET / HTTP"), "Expected request to reach server, got: $requestLine")
        }
    }

    @Test
    fun connect_certificateRevoked_closesConnectionBeforeSendingRequestData() = runTest {
        val ca = generateCa()
        val leaf = generateLeafCert(ca, san = "127.0.0.1")
        withTestServer(leaf, listOf(ca.cert)) { server ->
            val sdkClient = buildClient(server.port, trustedCa = ca, checker = revokedRevocationChecker())

            assertFailsWith<Exception> { sdkClient.get("/") }
            assertNoDataReachedServer(server)
        }
    }

    @Test
    fun connect_sanDoesNotMatchHost_closesConnectionBeforeSendingRequestData() = runTest {
        val ca = generateCa()
        val leaf = generateLeafCert(ca, san = "not-127.0.0.1")
        withTestServer(leaf, listOf(ca.cert)) { server ->
            val sdkClient = buildClient(server.port, trustedCa = ca, checker = passingRevocationChecker())

            assertFailsWith<Exception> { sdkClient.get("/") }
            assertNoDataReachedServer(server)
        }
    }

    @Test
    fun connect_certificateSignedByUntrustedCa_closesConnectionBeforeSendingRequestData() = runTest {
        val trustedCa = generateCa()
        val actualSigningCa = generateCa()
        val leaf = generateLeafCert(actualSigningCa, san = "127.0.0.1")
        withTestServer(leaf, listOf(actualSigningCa.cert)) { server ->
            val sdkClient = buildClient(server.port, trustedCa = trustedCa, checker = passingRevocationChecker())

            assertFailsWith<Exception> { sdkClient.get("/") }
            assertNoDataReachedServer(server)
        }
    }

    private suspend fun withTestServer(
        leaf: Leaf,
        chain: List<X509Certificate>,
        block: suspend (TestTlsServer) -> Unit,
    ) {
        val server = TestTlsServer(leaf, chain)
        try {
            block(server)
        } finally {
            server.stop()
        }
    }

    private fun buildClient(port: Int, trustedCa: Ca, checker: RevocationChecker): ZetaHttpClient =
        ZetaHttpClientBuilder("https://127.0.0.1:$port")
            .revocationChecker(checker)
            .addCaPem(trustedCa.cert.toPem())
            .build()

    private fun assertNoDataReachedServer(server: TestTlsServer) {
        val received = server.awaitReceivedBytes(timeoutMs = 3_000)
        assertTrue(
            received.isEmpty(),
            "Security gap: server received ${received.size} bytes: ${received.decodeToString().take(200)}",
        )
    }

    private fun passingRevocationChecker(): RevocationChecker =
        RevocationChecker(
            storage = RevocationStorage(InMemoryStorage(), ResourceScope("https://localhost", emptyList())),
            httpClient = ZetaHttpClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })),
            handler = mockk<RevocationHandler>(relaxed = true).also {
                every { it.extractCrlUrl(any()) } returns null
            },
            allowSkipForTestCertificates = true,
            clock = SystemZetaClock,
        )

    private fun revokedRevocationChecker(): RevocationChecker {
        val handler = mockk<RevocationHandler>()
        coEvery { handler.validate(any(), any(), any(), any()) } throws IllegalStateException("Certificate is revoked")

        return RevocationChecker(
            storage = RevocationStorage(InMemoryStorage(), ResourceScope("https://localhost", emptyList())),
            httpClient = ZetaHttpClient(HttpClient(MockEngine { error("unexpected network call: ${it.url}") })),
            handler = handler,
            clock = SystemZetaClock,
        )
    }

    private data class Ca(val cert: X509Certificate, val keyPair: KeyPair)
    private data class Leaf(val cert: X509Certificate, val keyPair: KeyPair)

    private fun generateCa(): Ca {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val subject = X500Name("CN=Test Revoked CA")
        val (now, notAfter) = validityWindow()
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(1), now, notAfter, subject, keyPair.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        return Ca(sign(builder as JcaX509v3CertificateBuilder, keyPair), keyPair)
    }

    private fun generateLeafCert(ca: Ca, san: String): Leaf {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val (now, notAfter) = validityWindow()
        val subject = X500Name("CN=$san")
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test Revoked CA"), BigInteger.valueOf(2), now, notAfter, subject, keyPair.public,
        ).addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(generalNameFor(san)),
        )
        return Leaf(sign(builder as JcaX509v3CertificateBuilder, ca.keyPair), keyPair)
    }

    private fun generalNameFor(san: String): GeneralName {
        val isIpAddress = san.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        val type = if (isIpAddress) GeneralName.iPAddress else GeneralName.dNSName
        return GeneralName(type, san)
    }

    private fun validityWindow(): Pair<Date, Date> {
        val now = Date()
        return now to Date(now.time + 365L * 24 * 3600 * 1000)
    }

    private fun sign(builder: JcaX509v3CertificateBuilder, signingKeyPair: KeyPair): X509Certificate {
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(signingKeyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun X509Certificate.toPem(): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded) +
            "\n-----END CERTIFICATE-----\n"

    private class TestTlsServer(leaf: Leaf, chain: List<X509Certificate>) {
        private val dataLatch = CountDownLatch(1)
        private val received = mutableListOf<Byte>()
        private val serverSocket: SSLServerSocket

        val port: Int

        init {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("leaf", leaf.keyPair.private, "test".toCharArray(), (listOf(leaf.cert) + chain).toTypedArray())
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, "test".toCharArray()) }
            val sslContext = SSLContext.getInstance("TLSv1.2").apply { init(kmf.keyManagers, null, null) }

            serverSocket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            port = serverSocket.localPort

            Thread(::acceptOnce, "zeta-test-tls-server").apply { isDaemon = true; start() }
        }

        private fun acceptOnce() {
            try {
                (serverSocket.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.startHandshake()

                    val buffer = ByteArray(4096)
                    val count = runCatching { socket.inputStream.read(buffer) }.getOrDefault(-1)
                    if (count > 0) {
                        received.addAll(buffer.copyOf(count).toList())
                        socket.outputStream.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                        )
                        socket.outputStream.flush()
                    }
                }
            } finally {
                dataLatch.countDown()
            }
        }

        fun awaitReceivedBytes(timeoutMs: Long): ByteArray {
            dataLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return received.toByteArray()
        }

        fun stop() {
            runCatching { serverSocket.close() }
        }
    }
}
