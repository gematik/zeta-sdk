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

import de.gematik.zeta.time.SystemZetaClock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZetaTrustManagerRevocationChainTest {
    private val rootAKeyPair = generateKeyPair()
    private val rootBKeyPair = generateKeyPair()
    private val subCaKeyPair = generateKeyPair()
    private val leafKeyPair = generateKeyPair()

    private val rootA = createCertificate("CN=Root A", rootAKeyPair, "CN=Root A", rootAKeyPair, isCa = true)
    private val rootB = createCertificate("CN=Root B", rootBKeyPair, "CN=Root B", rootBKeyPair, isCa = true)
    private val crossRootA = createCertificate("CN=Root A", rootAKeyPair, "CN=Root B", rootBKeyPair, isCa = true)
    private val subCa = createCertificate("CN=Sub-CA", subCaKeyPair, "CN=Root A", rootAKeyPair, isCa = true)
    private val leaf = createCertificate("CN=Leaf", leafKeyPair, "CN=Sub-CA", subCaKeyPair, isCa = false)

    @Test
    fun resolveRevocationChain_extraCertsAfterTrustAnchor_cutsAtTrustAnchor() {
        val result = resolveRevocationChain(
            trustStore = listOf(rootA, rootB),
            chain = listOf(leaf, subCa, rootA, crossRootA, rootB),
        )

        assertChainEquals(listOf(leaf, subCa, rootA), result)
    }

    @Test
    fun resolveRevocationChain_crossCertMatchingTrustedRoot_treatedAsTrustAnchor() {
        val result = resolveRevocationChain(
            trustStore = listOf(rootA, rootB),
            chain = listOf(leaf, subCa, crossRootA, rootB),
        )

        assertChainEquals(listOf(leaf, subCa, crossRootA), result)
    }

    @Test
    fun resolveRevocationChain_nonSelfSignedTrustAnchor_issuerNotAppended() {
        val result = resolveRevocationChain(
            trustStore = listOf(subCa, rootA),
            chain = listOf(leaf, subCa, rootA),
        )

        assertChainEquals(listOf(leaf, subCa), result)
    }

    @Test
    fun resolveRevocationChain_trustAnchorNotSent_appendsTrustAnchorFromTrustStore() {
        val result = resolveRevocationChain(
            trustStore = listOf(rootA),
            chain = listOf(leaf, subCa),
        )

        assertChainEquals(listOf(leaf, subCa, rootA), result)
    }

    @Test
    fun resolveRevocationChain_leafIsTrustAnchor_throwsCertificateException() {
        assertFailsWith<CertificateException> {
            resolveRevocationChain(trustStore = listOf(leaf), chain = listOf(leaf))
        }
    }

    @Test
    fun resolveRevocationChain_issuerNotInTrustStore_throwsCertificateException() {
        assertFailsWith<CertificateException> {
            resolveRevocationChain(trustStore = listOf(rootB), chain = listOf(leaf))
        }
    }

    private fun resolveRevocationChain(
        trustStore: List<X509Certificate>,
        chain: List<X509Certificate>,
    ): List<ByteArray> =
        ZetaTrustManager(FakeTrustManager(trustStore), clock = SystemZetaClock)
            .resolveRevocationChain(chain.toTypedArray())

    private fun assertChainEquals(expected: List<X509Certificate>, actual: List<ByteArray>) {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        assertEquals(
            expected.map { it.subjectX500Principal.name to it.issuerX500Principal.name },
            actual.map { der ->
                val certificate = certificateFactory.generateCertificate(der.inputStream()) as X509Certificate
                certificate.subjectX500Principal.name to certificate.issuerX500Principal.name
            },
        )
    }

    private class FakeTrustManager(private val trustAnchors: List<X509Certificate>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = trustAnchors.toTypedArray()
    }

    private companion object {
        private var nextSerialNumber = 1L

        fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        fun createCertificate(
            subject: String,
            subjectKeyPair: KeyPair,
            issuer: String,
            issuerKeyPair: KeyPair,
            isCa: Boolean,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                X500Name(issuer),
                BigInteger.valueOf(nextSerialNumber++),
                Date(now - 60_000),
                Date(now + 86_400_000),
                X500Name(subject),
                subjectKeyPair.public,
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeyPair.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }
}
