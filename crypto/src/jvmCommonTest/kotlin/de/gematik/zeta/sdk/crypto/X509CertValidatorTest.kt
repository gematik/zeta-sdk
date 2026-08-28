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

package de.gematik.zeta.sdk.crypto

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.isismtt.ISISMTTObjectIdentifiers
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax
import org.bouncycastle.asn1.isismtt.x509.Admissions
import org.bouncycastle.asn1.isismtt.x509.ProfessionInfo
import org.bouncycastle.asn1.x500.DirectoryString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class X509CertValidatorTest {

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val validator = X509CertValidator()

    private val keyGen = KeyPairGenerator.getInstance("EC", "BC")
        .apply { initialize(ECNamedCurveTable.getParameterSpec("brainpoolP256r1")) }

    private val caKeyPair: KeyPair = keyGen.generateKeyPair()
    private val caName = X500Name("CN=Test CA")
    private val now = Date()
    private val oneYearAgo = Date(now.time - 365L * 24 * 60 * 60 * 1000)
    private val oneYearFromNow = Date(now.time + 365L * 24 * 60 * 60 * 1000)
    private var serialCounter = 100L

    private val caCert: X509Certificate = buildCert(
        subject = caName,
        publicKey = caKeyPair.public,
    ) {
        addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    }

    private fun buildCert(
        subject: X500Name = X500Name("CN=Test Leaf"),
        issuer: X500Name = caName,
        signerKey: PrivateKey = caKeyPair.private,
        publicKey: PublicKey = keyGen.generateKeyPair().public,
        notBefore: Date = now,
        notAfter: Date = oneYearFromNow,
        configure: JcaX509v3CertificateBuilder.() -> Unit = {},
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(serialCounter++),
            notBefore,
            notAfter,
            subject,
            publicKey,
        )
        builder.configure()
        val signer = JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(signerKey)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
    }

    // region checkValidity

    @Test
    fun checkValidity_passesForCurrentlyValidCertificate() {
        val cert = buildCert()
        validator.checkValidity(cert.encoded)
    }

    @Test
    fun checkValidity_throwsForExpiredCertificate() {
        val cert = buildCert(notBefore = oneYearAgo, notAfter = Date(now.time - 1_000))
        assertFailsWith<CertificateExpiredException> {
            validator.checkValidity(cert.encoded)
        }
    }

    @Test
    fun checkValidity_throwsForNotYetValidCertificate() {
        val cert = buildCert(
            notBefore = Date(now.time + 60L * 60 * 1000),
            notAfter = oneYearFromNow,
        )
        assertFailsWith<CertificateNotYetValidException> {
            validator.checkValidity(cert.encoded)
        }
    }

    @Test
    fun getProfessionOids_returnsOidsFromAdmissionExtension() {
        val oid1 = "1.2.276.0.76.4.30"
        val oid2 = "1.2.276.0.76.4.31"
        val cert = buildCert {
            val professionInfo = ProfessionInfo(
                null,
                arrayOf(DirectoryString("Arzt")),
                arrayOf(ASN1ObjectIdentifier(oid1), ASN1ObjectIdentifier(oid2)),
                null,
                null,
            )
            val admissions = Admissions(null, null, arrayOf(professionInfo))
            val admissionSyntax = AdmissionSyntax(null, DERSequence(admissions))
            addExtension(
                ISISMTTObjectIdentifiers.id_isismtt_at_admission,
                false,
                admissionSyntax,
            )
        }

        assertEquals(listOf(oid1, oid2), validator.getProfessionOids(cert.encoded))
    }

    @Test
    fun getProfessionOids_returnsEmptyListWhenExtensionAbsent() {
        val cert = buildCert()
        assertTrue(validator.getProfessionOids(cert.encoded).isEmpty())
    }

    @Test
    fun getPublicKey_returnsEncodedPublicKeyOfCertificate() {
        val leafKeyPair = keyGen.generateKeyPair()
        val cert = buildCert(publicKey = leafKeyPair.public)

        assertContentEquals(leafKeyPair.public.encoded, validator.getPublicKey(cert.encoded))
        assertContentEquals(cert.publicKey.encoded, validator.getPublicKey(cert.encoded))
    }

    @Test
    fun validateCertChain_passesForChainAnchoredByTrustedCa() {
        val leaf = buildCert(subject = X500Name("CN=Trusted Leaf"))
        validator.validateCertChain(
            chainDer = listOf(leaf.encoded),
            trustAnchorsDer = listOf(caCert.encoded),
        )
    }

    @Test
    fun validateCertChain_throwsForEmptyChain() {
        assertFailsWith<IllegalArgumentException> {
            validator.validateCertChain(
                chainDer = emptyList(),
                trustAnchorsDer = listOf(caCert.encoded),
            )
        }
    }

    @Test
    fun validateCertChain_throwsWhenAnchorDoesNotSignChain() {
        val leaf = buildCert(subject = X500Name("CN=Untrusted Leaf"))
        val otherCaKeyPair = keyGen.generateKeyPair()
        val otherCa = buildCert(
            subject = X500Name("CN=Other CA"),
            signerKey = otherCaKeyPair.private,
            publicKey = otherCaKeyPair.public,
        ) {
            addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        }

        assertFailsWith<CertPathValidatorException> {
            validator.validateCertChain(
                chainDer = listOf(leaf.encoded),
                trustAnchorsDer = listOf(otherCa.encoded),
            )
        }
    }

    @Test
    fun getSanDnsNames_returnsOnlyDnsEntriesFromSanExtension() {
        val cert = buildCert {
            addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    arrayOf(
                        GeneralName(GeneralName.dNSName, "example.com"),
                        GeneralName(GeneralName.dNSName, "www.example.com"),
                        GeneralName(GeneralName.rfc822Name, "test@example.com"),
                    ),
                ),
            )
        }

        assertEquals(
            listOf("example.com", "www.example.com"),
            validator.getSanDnsNames(cert.encoded),
        )
    }

    @Test
    fun getSanDnsNames_returnsEmptyListWhenExtensionAbsent() {
        val cert = buildCert()
        assertTrue(validator.getSanDnsNames(cert.encoded).isEmpty())
    }
}
