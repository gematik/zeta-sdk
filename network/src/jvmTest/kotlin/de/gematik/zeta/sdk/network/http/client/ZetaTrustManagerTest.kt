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

import de.gematik.zeta.sdk.crypto.OcspRequestData
import de.gematik.zeta.sdk.crypto.OcspValidity
import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.BeforeClass
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Suppress("FunctionNaming")
class ZetaTrustManagerTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupBouncyCastle() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private val mockDelegate = mockk<X509TrustManager>(relaxed = true)

    private fun buildManager(revocationChecker: RevocationChecker? = null) =
        ZetaTrustManager(delegate = mockDelegate, revocationChecker = revocationChecker, SystemZetaClock)

    private val now = Date()
    private val caKeyPair by lazy {
        KeyPairGenerator.getInstance("EC", "BC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    }
    private val caName = X500Name("CN=Test CA")
    private val caSigner by lazy {
        JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(caKeyPair.private)
    }

    private fun validityWindow(): Pair<Date, Date> = now to Date(now.time + 365L * 24 * 60 * 60 * 1000)

    private fun buildCaCert(): X509Certificate {
        val (start, end) = validityWindow()
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(
            JcaX509v3CertificateBuilder(
                caName,
                BigInteger.valueOf(99),
                start,
                end,
                caName,
                caKeyPair.public,
            )
                .build(caSigner),
        )
    }

    private fun buildCertWithSan(san: String, serial: Long = 1L): X509Certificate {
        val (start, end) = validityWindow()
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(
            JcaX509v3CertificateBuilder(
                caName, BigInteger.valueOf(serial), start, end,
                X500Name("CN=$san"), caKeyPair.public,
            ).addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.dNSName, san)),
            ).build(caSigner),
        )
    }

    private fun buildCertWithoutSan(serial: Long = 2L): X509Certificate {
        val (start, end) = validityWindow()
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(
            JcaX509v3CertificateBuilder(
                caName,
                BigInteger.valueOf(serial),
                start,
                end,
                X500Name("CN=NoSan"),
                caKeyPair.public,
            )
                .build(caSigner),
        )
    }

    private fun buildExpiredCert(): X509Certificate {
        val pastStart = Date(now.time - 2 * 86_400_000L)
        val pastEnd = Date(now.time - 86_400_000L)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(
            JcaX509v3CertificateBuilder(
                caName,
                BigInteger.valueOf(3),
                pastStart,
                pastEnd,
                X500Name("CN=Expired"),
                caKeyPair.public,
            )
                .build(caSigner),
        )
    }

    private fun buildWeakSigAlgCert(): X509Certificate {
        val weakKeyPair = KeyPairGenerator.getInstance("EC", "BC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val weakSigner = JcaContentSignerBuilder("SHA1WithECDSA").setProvider("BC").build(weakKeyPair.private)
        val (start, end) = validityWindow()
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(
            JcaX509v3CertificateBuilder(
                caName,
                BigInteger.valueOf(4),
                start,
                end,
                X500Name("CN=Weak"),
                weakKeyPair.public,
            )
                .build(weakSigner),
        )
    }

    private fun buildValidCert(serial: Long = 1L): X509Certificate =
        buildCertWithSan(san = "valid.example.com", serial = serial)

    private fun buildExtendedSession(peerHost: String?, staple: ByteArray?): ExtendedSSLSession =
        mockk<ExtendedSSLSession>().also { session ->
            every { session.peerHost } returns peerHost
            every { session.statusResponses } returns if (staple != null) listOf(staple) else emptyList()
        }

    private fun buildSocket(peerHost: String? = "valid.example.com", staple: ByteArray? = null): SSLSocket =
        mockk<SSLSocket>(relaxed = true).also { socket ->
            every { socket.handshakeSession } returns buildExtendedSession(peerHost, staple)
        }

    private fun buildEngine(peerHost: String? = "valid.example.com", staple: ByteArray? = null): SSLEngine =
        mockk<SSLEngine>(relaxed = true).also { engine ->
            every { engine.handshakeSession } returns buildExtendedSession(peerHost, staple)
        }

    private fun buildRevocationChecker(
        handler: RevocationHandler,
        httpClient: ZetaHttpClient =
            ZetaHttpClient(HttpClient(MockEngine.Companion { error("unexpected network call: ${it.url}") })),
    ): RevocationChecker =
        RevocationChecker(
            storage = RevocationStorage(
                InMemoryStorage(),
                ResourceScope("https://localhost", emptyList()),
            ),
            httpClient = httpClient,
            handler = handler,
            clock = SystemZetaClock,
        )

    private fun passingHandlerForStaple(): RevocationHandler = mockk<RevocationHandler>(relaxed = true).also { handler ->
        every { handler.getOcspValidity(any(), any(), any()) } returns OcspValidity(
            thisUpdateEpochSeconds = Clock.System.now().epochSeconds,
            nextUpdateEpochSeconds = Clock.System.now().epochSeconds + 3600,
        )
        every { handler.validate(any(), any(), any(), any()) } returns Unit
    }

    @Test
    fun checkServerTrusted_callsDelegate_whenChainIsValid() {
        val chain = arrayOf(buildValidCert())
        buildManager().checkServerTrusted(chain, "RSA")
        verify { mockDelegate.checkServerTrusted(chain, "RSA") }
    }

    @Test
    fun checkServerTrusted_forwardsExactChainAndAuthType_toDelegate() {
        val chain = arrayOf(buildValidCert())
        val authType = "ECDHE_ECDSA"
        buildManager().checkServerTrusted(chain, authType)
        verify { mockDelegate.checkServerTrusted(chain, authType) }
    }

    @Test
    fun checkServerTrusted_throwsCertificateException_whenCertIsExpired() {
        assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(arrayOf(buildExpiredCert()), "RSA")
        }
    }

    @Test
    fun checkServerTrusted_throwsCertificateException_whenCertHasForbiddenSigAlg() {
        assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(arrayOf(buildWeakSigAlgCert()), "RSA")
        }
    }

    @Test
    fun checkServerTrusted_stillCallsDelegate_whenChainValidationFails() {
        runCatching { buildManager().checkServerTrusted(arrayOf(buildExpiredCert()), "RSA") }
        verify(exactly = 1) { mockDelegate.checkServerTrusted(any(), any()) }
    }

    @Test
    fun checkServerTrusted_certificateExceptionMessage_containsGematikPrefix() {
        val ex = assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(arrayOf(buildExpiredCert()), "RSA")
        }
        assertEquals(
            ex.message?.contains("gematik cert validation failed"),
            true,
            "Expected gematik prefix in: ${ex.message}",
        )
    }

    @Test
    fun checkServerTrusted_propagatesCertificateException_whenDelegateThrows() {
        every {
            mockDelegate.checkServerTrusted(
                any(),
                any(),
            )
        } throws CertificateException("untrusted root")
        assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(arrayOf(buildValidCert()), "RSA")
        }
    }

    @Test
    fun checkServerTrusted_socket_doesNotThrow_whenSanMatchesPeerHost() {
        val chain = arrayOf(buildValidCert())
        val socket = buildSocket(peerHost = "valid.example.com")

        buildManager().checkServerTrusted(chain, "RSA", socket)

        verify { mockDelegate.checkServerTrusted(chain, "RSA") }
    }

    @Test
    fun checkServerTrusted_socket_throwsCertificateException_whenSanDoesNotMatchPeerHost() {
        val chain = arrayOf(buildValidCert())
        val socket = buildSocket(peerHost = "other.example.com")

        assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(chain, "RSA", socket)
        }
    }

    @Test
    fun checkServerTrusted_socket_throwsCertificateException_whenCertHasNoSanEntries() {
        val chain = arrayOf(buildCertWithoutSan())
        val socket = buildSocket(peerHost = "valid.example.com")

        assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(chain, "RSA", socket)
        }
    }

    @Test
    fun checkServerTrusted_socket_throwsCertificateException_whenPeerHostIsNull() {
        val chain = arrayOf(buildValidCert())
        val socket = buildSocket(peerHost = null)

        assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(chain, "RSA", socket)
        }
    }

    @Test
    fun checkServerTrusted_socket_delegatesToExtendedTrustManager_whenDelegateIsExtended() {
        val extendedDelegate = mockk<X509ExtendedTrustManager>(relaxed = true)
        val manager = ZetaTrustManager(delegate = extendedDelegate, clock = SystemZetaClock)
        val chain = arrayOf(buildValidCert())
        val socket = buildSocket(peerHost = "valid.example.com")

        manager.checkServerTrusted(chain, "RSA", socket)

        verify { extendedDelegate.checkServerTrusted(chain, "RSA", socket) }
        verify(exactly = 0) { extendedDelegate.checkServerTrusted(chain, "RSA") }
    }

    @Test
    fun checkServerTrusted_engine_doesNotThrow_whenSanMatchesPeerHost() {
        val chain = arrayOf(buildValidCert())
        val engine = buildEngine(peerHost = "valid.example.com")

        buildManager().checkServerTrusted(chain, "RSA", engine)

        verify { mockDelegate.checkServerTrusted(chain, "RSA") }
    }

    @Test
    fun checkServerTrusted_engine_throwsCertificateException_whenSanDoesNotMatchPeerHost() {
        val chain = arrayOf(buildValidCert())
        val engine = buildEngine(peerHost = "other.example.com")

        assertFailsWith<CertificateException> {
            buildManager().checkServerTrusted(chain, "RSA", engine)
        }
    }

    @Test
    fun checkServerTrusted_socket_passesExtractedStaple_toRevocationHandler() = runTest {
        val stapleBytes = byteArrayOf(1, 2, 3, 4)
        val handler = passingHandlerForStaple()
        val checker = buildRevocationChecker(handler)

        val chain = arrayOf(buildValidCert(), buildCaCert())
        val socket = buildSocket(peerHost = "valid.example.com", staple = stapleBytes)

        buildManager(revocationChecker = checker).checkServerTrusted(chain, "RSA", socket)

        coVerify(exactly = 1) { handler.validate(stapleBytes, chain[0].encoded, any(), any()) }
    }

    @Test
    fun checkServerTrusted_engine_passesExtractedStaple_toRevocationHandler() = runTest {
        val stapleBytes = byteArrayOf(9, 9, 9)
        val handler = passingHandlerForStaple()
        val checker = buildRevocationChecker(handler)

        val chain = arrayOf(buildValidCert(), buildCaCert())
        val engine = buildEngine(peerHost = "valid.example.com", staple = stapleBytes)

        buildManager(revocationChecker = checker).checkServerTrusted(chain, "RSA", engine)

        coVerify(exactly = 1) { handler.validate(stapleBytes, chain[0].encoded, any(), any()) }
    }

    @Test
    fun checkServerTrusted_socket_attemptsDirectOcsp_whenNoStapleIsPresent() = runTest {
        val handler = mockk<RevocationHandler>(relaxed = true)
        coEvery { handler.prepareOcspRequest(any(), any()) } returns OcspRequestData(
            "https://ocsp.example.com", byteArrayOf(1),
        )
        every { handler.getOcspValidity(any(), any(), any()) } returns OcspValidity(
            thisUpdateEpochSeconds = Clock.System.now().epochSeconds,
            nextUpdateEpochSeconds = Clock.System.now().epochSeconds + 3600,
        )
        every { handler.validate(any(), any(), any(), any()) } returns Unit

        val ocspResponseBytes = ByteArray(64) { it.toByte() }
        val httpClient = ZetaHttpClient(
            HttpClient(
                MockEngine.Companion { _ ->
                    respond(
                        content = ocspResponseBytes,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/ocsp-response"),
                    )
                },
            ),
        )
        val checker = buildRevocationChecker(handler, httpClient = httpClient)

        val chain = arrayOf(buildValidCert(), buildCaCert())
        val socket = buildSocket(peerHost = "valid.example.com", staple = null)

        buildManager(revocationChecker = checker).checkServerTrusted(chain, "RSA", socket)

        coVerify(exactly = 1) { handler.prepareOcspRequest(any(), any()) }
    }

    @Test
    fun checkServerTrusted_socket_throwsCertificateException_whenRevocationCheckFails() {
        val handler = mockk<RevocationHandler>(relaxed = true)
        every { handler.getOcspValidity(any(), any(), any()) } returns OcspValidity(
            thisUpdateEpochSeconds = Clock.System.now().epochSeconds,
            nextUpdateEpochSeconds = Clock.System.now().epochSeconds + 3600,
        )
        every { handler.validate(any(), any(), any(), any()) } throws IllegalStateException("Certificate is revoked")
        val checker = buildRevocationChecker(handler)

        val chain = arrayOf(buildValidCert(), buildCaCert())
        val socket = buildSocket(peerHost = "valid.example.com", staple = byteArrayOf(1))

        assertFailsWith<CertificateException> {
            buildManager(revocationChecker = checker).checkServerTrusted(chain, "RSA", socket)
        }
    }

    @Test
    @Ignore // only for local tests
    fun checkServerTrusted_socket_throwsCertificateException_whenRevocationCheckExceedsTimeout() =
        runTest {
            val handler = mockk<RevocationHandler>(relaxed = true)
            every { handler.getOcspValidity(any(), any(), any()) } returns OcspValidity(
                thisUpdateEpochSeconds = Clock.System.now().epochSeconds,
                nextUpdateEpochSeconds = Clock.System.now().epochSeconds + 3600,
            )
            coEvery { handler.validate(any(), any(), any(), any()) } coAnswers {
                delay(10_000.milliseconds)
                error("should never resolve")
            }
            val checker = buildRevocationChecker(handler)

            val chain = arrayOf(buildValidCert(), buildCaCert())
            val socket = buildSocket(peerHost = "valid.example.com", staple = byteArrayOf(1))

            assertFailsWith<CertificateException> {
                buildManager(revocationChecker = checker).checkServerTrusted(chain, "RSA", socket)
            }
        }

    @Test
    fun checkServerTrusted_socket_doesNotThrowForRevocation_whenNoCheckerConfigured() {
        val chain = arrayOf(buildValidCert())
        val socket = buildSocket(peerHost = "valid.example.com")

        buildManager(revocationChecker = null).checkServerTrusted(chain, "RSA", socket)

        verify { mockDelegate.checkServerTrusted(chain, "RSA") }
    }

    @Test
    fun checkClientTrusted_delegatesToDelegate() {
        val chain = arrayOf(buildValidCert())
        buildManager().checkClientTrusted(chain, "RSA")
        verify { mockDelegate.checkClientTrusted(chain, "RSA") }
    }

    @Test
    fun checkClientTrusted_socket_delegatesToExtendedTrustManager_whenDelegateIsExtended() {
        val extendedDelegate = mockk<X509ExtendedTrustManager>(relaxed = true)
        val manager = ZetaTrustManager(delegate = extendedDelegate, clock = SystemZetaClock)
        val chain = arrayOf(buildValidCert())
        val socket = mockk<SSLSocket>(relaxed = true)

        manager.checkClientTrusted(chain, "RSA", socket)

        verify { extendedDelegate.checkClientTrusted(chain, "RSA", socket) }
    }

    @Test
    fun checkClientTrusted_socket_fallsBackToTwoArgDelegate_whenDelegateIsNotExtended() {
        val chain = arrayOf(buildValidCert())
        val socket = mockk<SSLSocket>(relaxed = true)

        buildManager().checkClientTrusted(chain, "RSA", socket)

        verify { mockDelegate.checkClientTrusted(chain, "RSA") }
    }

    @Test
    fun checkClientTrusted_engine_delegatesToExtendedTrustManager_whenDelegateIsExtended() {
        val extendedDelegate = mockk<X509ExtendedTrustManager>(relaxed = true)
        val manager = ZetaTrustManager(delegate = extendedDelegate, clock = SystemZetaClock)
        val chain = arrayOf(buildValidCert())
        val engine = mockk<SSLEngine>(relaxed = true)

        manager.checkClientTrusted(chain, "RSA", engine)

        verify { extendedDelegate.checkClientTrusted(chain, "RSA", engine) }
    }

    @Test
    fun getAcceptedIssuers_delegatesToDelegate() {
        val issuers = arrayOf(buildValidCert())
        every { mockDelegate.acceptedIssuers } returns issuers
        assertContentEquals(issuers, buildManager().acceptedIssuers)
    }

    @Test
    fun getAcceptedIssuers_returnsEmptyArray_whenDelegateReturnsEmpty() {
        every { mockDelegate.acceptedIssuers } returns emptyArray()
        assertTrue(buildManager().acceptedIssuers.isEmpty())
    }

    @Test
    fun checkServerTrusted_revocationChain_addsIssuerFromTrustStore_whenOnlyLeafIsProvided() {
        // Arrange
        val leaf = buildValidCert()
        val issuer = buildCaCert()
        val checker = mockk<RevocationChecker>()

        every { mockDelegate.acceptedIssuers } returns arrayOf(issuer)
        coEvery {
            checker.validateChain(
                stapledOcspResponse = any(),
                chain = any(),
            )
        } returns Unit

        val manager = buildManager(revocationChecker = checker)
        val capturedChain = slot<List<ByteArray>>()

        // Act
        manager.checkServerTrusted(
            chain = arrayOf(leaf),
            authType = "ECDHE_ECDSA",
        )

        // Assert
        coVerify(exactly = 1) {
            checker.validateChain(
                stapledOcspResponse = null,
                chain = capture(capturedChain),
            )
        }

        assertEquals(2, capturedChain.captured.size)
        assertContentEquals(leaf.encoded, capturedChain.captured[0])
        assertContentEquals(issuer.encoded, capturedChain.captured[1])
    }

    @Test
    fun findIssuer_returnsMatchingIssuer_whenSubjectAndSignatureMatch() {
        val issuer = buildCaCert()
        val leaf = buildValidCert()

        every { mockDelegate.acceptedIssuers } returns arrayOf(issuer)

        val result = buildManager().findIssuer(leaf)

        assertEquals(issuer, result)
    }

    @Test
    fun findIssuer_returnsNull_whenAcceptedIssuersIsEmpty() {
        val leaf = buildValidCert()

        every { mockDelegate.acceptedIssuers } returns emptyArray()

        val result = buildManager().findIssuer(leaf)

        assertEquals(null, result)
    }

    @Test
    fun isSelfSigned_returnsTrue_whenSubjectIssuerAndSignatureMatch() {
        val certificate = buildCaCert()
        val manager = buildManager()

        val result = with(manager) {
            certificate.isSelfSigned()
        }

        assertTrue(result)
    }

    @Test
    fun isSelfSigned_returnsFalse_whenSubjectDiffersFromIssuer() {
        val certificate = buildValidCert()
        val manager = buildManager()

        val result = with(manager) {
            certificate.isSelfSigned()
        }

        assertEquals(false, result)
    }

    @Test
    fun checkServerTrusted_socket_acceptsStaple_whenNoNextUpdate_withinTwentyFourHours() = runTest {
        val stapleBytes = byteArrayOf(1, 2, 3, 4)
        val handler = mockk<RevocationHandler>().also {
            every { it.getOcspValidity(any(), any(), any()) } returns OcspValidity(
                thisUpdateEpochSeconds = Clock.System.now().epochSeconds - 3600 * 10,
                nextUpdateEpochSeconds = null,
            )
            every { it.validate(any(), any(), any(), any()) } returns Unit
        }
        val checker = buildRevocationChecker(handler)

        val chain = arrayOf(buildValidCert(), buildCaCert())
        val socket = buildSocket(peerHost = "valid.example.com", staple = stapleBytes)

        buildManager(revocationChecker = checker).checkServerTrusted(chain, "RSA", socket)

        coVerify(exactly = 1) { handler.validate(stapleBytes, chain[0].encoded, any(), any()) }
    }
}
