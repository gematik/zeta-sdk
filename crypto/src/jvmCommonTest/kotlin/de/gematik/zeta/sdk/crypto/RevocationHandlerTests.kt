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

import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.CRLReason
import org.bouncycastle.asn1.x509.DistributionPoint
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder
import org.bouncycastle.cert.ocsp.CertificateID
import org.bouncycastle.cert.ocsp.CertificateStatus
import org.bouncycastle.cert.ocsp.OCSPRespBuilder
import org.bouncycastle.cert.ocsp.RespID
import org.bouncycastle.cert.ocsp.RevokedStatus
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class RevocationHandlerTest {

    private val handler = RevocationHandlerImpl()

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val keyGen = KeyPairGenerator.getInstance("EC", "BC")
        .apply { initialize(org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("brainpoolP256r1")) }

    private val caKeyPair: KeyPair = keyGen.generateKeyPair()

    private val caName = X500Name("CN=Test CA")
    private val caSigner = JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(caKeyPair.private)
    private val now = Date()
    private val oneYear = Date(now.time + 365L * 24 * 60 * 60 * 1000)

    private val caCert: X509Certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(
        JcaX509v3CertificateBuilder(caName, BigInteger.ONE, now, oneYear, caName, caKeyPair.public)
            .apply { addExtension(Extension.basicConstraints, true, BasicConstraints(true)) }
            .build(caSigner),
    )

    private val leafCert: X509Certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(
        JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(2), now, oneYear, X500Name("CN=Test Leaf"), keyGen.generateKeyPair().public)
            .apply {
                addExtension(
                    Extension.authorityInfoAccess, false,
                    AuthorityInformationAccess(
                        AccessDescription(
                            AccessDescription.id_ad_ocsp,
                            GeneralName(GeneralName.uniformResourceIdentifier, OCSP_URL),
                        ),
                    ),
                )
                addExtension(
                    Extension.cRLDistributionPoints, false,
                    CRLDistPoint(
                        arrayOf(
                            DistributionPoint(
                                DistributionPointName(
                                    DistributionPointName.FULL_NAME,
                                    GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, CRL_URL)),
                                ),
                                null, null,
                            ),
                        ),
                    ),
                )
            }
            .build(caSigner),
    )

    private val revokedCert: X509Certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(
        JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(3), now, oneYear, X500Name("CN=Revoked Cert"), keyGen.generateKeyPair().public)
            .build(caSigner),
    )

    private val certWithoutExtensions: X509Certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(
        JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(4), now, oneYear, X500Name("CN=No Extensions Cert"), keyGen.generateKeyPair().public)
            .build(caSigner),
    )

    private fun buildOcspResponseDer(
        cert: X509Certificate,
        issuer: X509Certificate = caCert,
        certStatus: CertificateStatus? = CertificateStatus.GOOD,
        producedAt: Date = Date(),
        thisUpdate: Date = Date(System.currentTimeMillis() - 1_000),
        nextUpdate: Date? = Date(System.currentTimeMillis() + 86_400_000),
        signerKey: java.security.PrivateKey = caKeyPair.private,
        signerCert: X509Certificate = caCert,
    ): ByteArray {
        val digestCalcProvider = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val certId = CertificateID(
            digestCalcProvider.get(CertificateID.HASH_SHA1),
            JcaX509CertificateHolder(issuer),
            cert.serialNumber,
        )
        val respBuilder = BasicOCSPRespBuilder(RespID(JcaX509CertificateHolder(signerCert).subject))
        respBuilder.addResponse(certId, certStatus, thisUpdate, nextUpdate, null)
        val signer = JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(signerKey)
        val basicResp = respBuilder.build(signer, arrayOf(JcaX509CertificateHolder(signerCert)), producedAt)
        return OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basicResp).encoded
    }

    private fun buildCrlDer(
        revokedSerials: List<BigInteger> = emptyList(),
        thisUpdate: Date = Date(System.currentTimeMillis() - 1_000),
        nextUpdate: Date = Date(System.currentTimeMillis() + 86_400_000),
    ): ByteArray {
        val crlBuilder = X509v2CRLBuilder(X500Name(caCert.subjectX500Principal.name), thisUpdate)
            .apply {
                setNextUpdate(nextUpdate)
                revokedSerials.forEach { addCRLEntry(it, Date(), CRLReason.unspecified) }
            }
        val signer = JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(caKeyPair.private)
        return JcaX509CRLConverter().setProvider("BC").getCRL(crlBuilder.build(signer)).encoded
    }

    private fun buildOcspResponse(
        cert: X509Certificate = leafCert,
        nextUpdate: Date?,
        thisUpdate: Date = now,
        status: CertificateStatus? = null, // null = GOOD in Bouncy Castle
    ): ByteArray {
        val digestCalc = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val caHolder = JcaX509CertificateHolder(caCert)
        val certId = CertificateID(
            digestCalc[CertificateID.HASH_SHA1],
            caHolder,
            cert.serialNumber,
        )
        val basicGen = BasicOCSPRespBuilder(RespID(caHolder.subject))
        basicGen.addResponse(certId, status, thisUpdate, nextUpdate)
        val basicResp = basicGen.build(caSigner, null, thisUpdate)
        return OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basicResp).encoded
    }

    private fun buildDelegatedSignerCert(
        signingKeyPair: KeyPair = caKeyPair,
        subjectName: X500Name = X500Name("CN=Delegated OCSP Responder"),
        includeOcspSigningEku: Boolean = true,
    ): Pair<X509Certificate, KeyPair> {
        val signerKeyPair = KeyPairGenerator.getInstance("EC", "BC")
            .apply { initialize(org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("brainpoolP256r1")) }
            .generateKeyPair()

        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test CA"),
            BigInteger.valueOf(100),
            now,
            oneYear,
            subjectName,
            signerKeyPair.public,
        )

        if (includeOcspSigningEku) {
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_OCSPSigning),
            )
        }

        val signer = JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(signingKeyPair.private)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
        return cert to signerKeyPair
    }

    @Test
    fun `getThisUpdateEpochSeconds returns correct timestamp`() {
        val thisUpdate = Date(1_700_000_000_000L)

        val ocspDer = buildOcspResponseDer(
            cert = leafCert,
            thisUpdate = thisUpdate,
        )

        assertEquals(
            1_700_000_000L,
            handler.getThisUpdateEpochSeconds(ocspDer),
        )
    }

    @Test
    fun `validate passes for good certificate`() {
        val ocspDer = buildOcspResponseDer(leafCert)
        handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
    }

    @Test
    fun `validate throws when certificate is revoked`() {
        val ocspDer = buildOcspResponseDer(revokedCert, certStatus = RevokedStatus(Date(), CRLReason.unspecified))
        assertFailsWith<IllegalArgumentException> {
            handler.validate(ocspDer, revokedCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun `validate throws when OCSP response is expired`() {
        val ocspDer = buildOcspResponseDer(
            leafCert,
            thisUpdate = Date(System.currentTimeMillis() - 2 * 86_400_000),
            nextUpdate = Date(System.currentTimeMillis() - 86_400_000),
        )
        assertFailsWith<IllegalArgumentException> {
            handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun `validate throws when OCSP thisUpdate is in the future`() {
        val ocspDer = buildOcspResponseDer(
            leafCert,
            thisUpdate = Date(System.currentTimeMillis() + 86_400_000),
            nextUpdate = Date(System.currentTimeMillis() + 2 * 86_400_000),
        )
        assertFailsWith<IllegalArgumentException> {
            handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun `validate throws when OCSP signature is invalid`() {
        val wrongKeyPair = KeyPairGenerator.getInstance("EC", "BC")
            .apply { initialize(org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("brainpoolP256r1")) }
            .generateKeyPair()
        val ocspDer = buildOcspResponseDer(leafCert, signerKey = wrongKeyPair.private)
        assertFailsWith<IllegalArgumentException> {
            handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun `prepareOcspRequest returns correct OCSP URL`() = runBlocking {
        val result = handler.prepareOcspRequest(leafCert.encoded, caCert.encoded)
        assertEquals(OCSP_URL, result.url)
    }

    @Test
    fun `prepareOcspRequest returns non-empty DER`() = runBlocking {
        val result = handler.prepareOcspRequest(leafCert.encoded, caCert.encoded)
        assertTrue(result.requestDer.isNotEmpty())
    }

    @Test
    fun `prepareOcspRequest throws when cert has no AIA extension`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> {
            handler.prepareOcspRequest(certWithoutExtensions.encoded, caCert.encoded)
        }
    }

    @Test
    fun `extractCrlUrl returns correct URL from distribution points`() {
        assertEquals(CRL_URL, handler.extractCrlUrl(leafCert.encoded))
    }

    @Test
    fun `extractCrlUrl returns null when cert has no CRL distribution points`() {
        assertNull(handler.extractCrlUrl(certWithoutExtensions.encoded))
    }

    @Test
    fun `validateCrl passes for valid CRL and non-revoked certificate`() {
        handler.validateCrl(buildCrlDer(), leafCert.encoded, caCert.encoded)
    }

    @Test
    fun `validateCrl throws when certificate serial is in CRL`() {
        assertFailsWith<IllegalStateException> {
            handler.validateCrl(buildCrlDer(revokedSerials = listOf(leafCert.serialNumber)), leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun `validateCrl throws when CRL nextUpdate is in the past`() {
        val crlDer = buildCrlDer(
            thisUpdate = Date(System.currentTimeMillis() - 2 * 86_400_000),
            nextUpdate = Date(System.currentTimeMillis() - 86_400_000),
        )
        assertFailsWith<IllegalArgumentException> {
            handler.validateCrl(crlDer, leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun `validateCrl throws when CRL thisUpdate is in the future`() {
        val crlDer = buildCrlDer(
            thisUpdate = Date(System.currentTimeMillis() + 86_400_000),
            nextUpdate = Date(System.currentTimeMillis() + 2 * 86_400_000),
        )
        assertFailsWith<IllegalArgumentException> {
            handler.validateCrl(crlDer, leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun getNextUpdateEpochSeconds_returnsNull_whenCertSerialNotInResponse() {
        val ocspDer = buildOcspResponse(
            cert = leafCert,
            nextUpdate = Date(now.time + 3600_000L),
        )

        val result = handler.getNextUpdateEpochSeconds(
            ocspResponseDer = ocspDer,
            certDer = revokedCert.encoded,
            issuerDer = caCert.encoded,
        )

        assertNull(result)
    }

    @Test
    fun getNextUpdateEpochSeconds_returnsExpiredValue_whenNextUpdateInPast() {
        val yesterday = Date(now.time - 24 * 3600 * 1000L)
        val ocspDer = buildOcspResponse(nextUpdate = yesterday)

        val result = handler.getNextUpdateEpochSeconds(
            ocspResponseDer = ocspDer,
            certDer = leafCert.encoded,
            issuerDer = caCert.encoded,
        )

        assertNotNull(result)
        assert(result < now.time / 1000) { "nextUpdate should be in the past" }
    }

    @Test
    fun getNextUpdateEpochSeconds_returnsOneWeek_forPoPPScenario() {
        val producedAt = Date(now.time - 3 * 24 * 3600 * 1000L)
        val nextUpdate = Date(now.time + 4 * 24 * 3600 * 1000L)
        val ocspDer = buildOcspResponse(
            nextUpdate = nextUpdate,
            thisUpdate = producedAt,
        )

        val result = handler.getNextUpdateEpochSeconds(
            ocspResponseDer = ocspDer,
            certDer = leafCert.encoded,
            issuerDer = caCert.encoded,
        )

        assertNotNull(result)
        assert(result > now.time / 1000) { "nextUpdate should be in the future" }
        assert(result < now.time / 1000 + 5 * 24 * 3600) { "nextUpdate should be 4 days from now" }
    }

    @Test
    fun `getCrlNextUpdateEpochSeconds returns correct timestamp`() {
        val nextUpdate = Date(1_700_000_000_000L)
        val crlDer = buildCrlDer(nextUpdate = nextUpdate)

        val result = handler.getCrlNextUpdateEpochSeconds(crlDer)

        assertEquals(1_700_000_000L, result)
    }

    @Test
    fun `getCrlNextUpdateEpochSeconds returns future timestamp for valid CRL`() {
        val nextUpdate = Date(System.currentTimeMillis() + 7 * 86_400_000)
        val crlDer = buildCrlDer(nextUpdate = nextUpdate)

        val result = handler.getCrlNextUpdateEpochSeconds(crlDer)

        assertNotNull(result)
        assertTrue(result > System.currentTimeMillis() / 1000)
    }

    @Test
    fun `getCrlNextUpdateEpochSeconds returns past timestamp for expired CRL`() {
        val nextUpdate = Date(System.currentTimeMillis() - 86_400_000)
        val crlDer = buildCrlDer(nextUpdate = nextUpdate)

        val result = handler.getCrlNextUpdateEpochSeconds(crlDer)

        assertNotNull(result)
        assertTrue(result < System.currentTimeMillis() / 1000)
    }

    @Test
    fun getThisUpdateEpochSeconds_throws_whenNoSingleResponsePresent() {
        // A response with no addResponse(...) call at all — zero SingleResponse entries.
        val respBuilder = BasicOCSPRespBuilder(RespID(JcaX509CertificateHolder(caCert).subject))
        val signer = JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(caKeyPair.private)
        val basicResp = respBuilder.build(signer, arrayOf(JcaX509CertificateHolder(caCert)), Date())
        val ocspDer = OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basicResp).encoded

        assertFailsWith<IllegalStateException> {
            handler.getThisUpdateEpochSeconds(ocspDer)
        }
    }

    @Test
    fun getThisUpdateEpochSeconds_throws_whenDerIsMalformed() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        assertFailsWith<Exception> {
            handler.getThisUpdateEpochSeconds(garbage)
        }
    }

    @Test
    fun getThisUpdateEpochSeconds_truncatesToWholeSeconds_whenSubSecondPrecisionPresent() {
        // Date only has millisecond precision — verify the conversion floors to the second, not rounds.
        val thisUpdate = Date(1_700_000_000_500L) // .5 seconds past the whole second
        val ocspDer = buildOcspResponseDer(leafCert, thisUpdate = thisUpdate)

        assertEquals(1_700_000_000L, handler.getThisUpdateEpochSeconds(ocspDer))
    }

    @Test
    fun getThisUpdateEpochSeconds_returnsFirstResponse_whenMultipleResponsesPresent() {
        val digestCalcProvider = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val issuerHolder = JcaX509CertificateHolder(caCert)
        val respBuilder = BasicOCSPRespBuilder(RespID(issuerHolder.subject))

        val firstThisUpdate = Date(1_700_000_000_000L)
        val secondThisUpdate = Date(1_800_000_000_000L)

        respBuilder.addResponse(
            CertificateID(digestCalcProvider.get(CertificateID.HASH_SHA1), issuerHolder, leafCert.serialNumber),
            CertificateStatus.GOOD,
            firstThisUpdate,
            Date(firstThisUpdate.time + 86_400_000),
            null,
        )
        respBuilder.addResponse(
            CertificateID(digestCalcProvider.get(CertificateID.HASH_SHA1), issuerHolder, revokedCert.serialNumber),
            CertificateStatus.GOOD,
            secondThisUpdate,
            Date(secondThisUpdate.time + 86_400_000),
            null,
        )

        val signer = JcaContentSignerBuilder("SHA256WithECDSA").setProvider("BC").build(caKeyPair.private)
        val basicResp = respBuilder.build(signer, arrayOf(JcaX509CertificateHolder(caCert)), Date())
        val ocspDer = OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basicResp).encoded

        assertEquals(1_700_000_000L, handler.getThisUpdateEpochSeconds(ocspDer))
    }

    @Test
    fun getThisUpdateEpochSeconds_returnsZero_whenThisUpdateIsAtEpoch() {
        val ocspDer = buildOcspResponseDer(leafCert, thisUpdate = Date(0L))

        assertEquals(0L, handler.getThisUpdateEpochSeconds(ocspDer))
    }

    @Test
    fun validate_passes_whenSignerIsTheIssuerItself() {
        // signerCert defaults to caCert in buildOcspResponseDer — this is the baseline "no delegation" case
        val ocspDer = buildOcspResponseDer(leafCert, signerKey = caKeyPair.private, signerCert = caCert)
        handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
    }

    @Test
    fun validate_passes_whenDelegatedSignerIsAuthorizedByIssuerAndHasOcspSigningEku() {
        val (delegatedCert, delegatedKeyPair) = buildDelegatedSignerCert(
            signingKeyPair = caKeyPair,
            includeOcspSigningEku = true,
        )
        val ocspDer = buildOcspResponseDer(
            leafCert,
            signerKey = delegatedKeyPair.private,
            signerCert = delegatedCert,
        )

        handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
    }

    @Test
    fun validate_throws_whenDelegatedSignerIsAuthorizedByIssuerButMissingOcspSigningEku() {
        val (delegatedCert, delegatedKeyPair) = buildDelegatedSignerCert(
            signingKeyPair = caKeyPair,
            includeOcspSigningEku = false,
        )
        val ocspDer = buildOcspResponseDer(
            leafCert,
            signerKey = delegatedKeyPair.private,
            signerCert = delegatedCert,
        )

        assertFailsWith<IllegalArgumentException> {
            handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun validate_throws_whenEmbeddedSignerIsNotSignedByTheIssuerAtAll() {
        val unrelatedCaKeyPair = KeyPairGenerator.getInstance("EC", "BC")
            .apply { initialize(org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("brainpoolP256r1")) }
            .generateKeyPair()

        val (unauthorizedSignerCert, unauthorizedSignerKeyPair) = buildDelegatedSignerCert(
            signingKeyPair = unrelatedCaKeyPair,
            includeOcspSigningEku = true,
        )

        val ocspDer = buildOcspResponseDer(
            leafCert,
            signerKey = unauthorizedSignerKeyPair.private,
            signerCert = unauthorizedSignerCert,
        )

        assertFailsWith<IllegalArgumentException> {
            handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
        }
    }

    @Test
    fun validate_throws_whenEmbeddedSignerIsSelfSignedAndUnrelatedToIssuer() {
        val attackerKeyPair = KeyPairGenerator.getInstance("EC", "BC")
            .apply { initialize(org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("brainpoolP256r1")) }
            .generateKeyPair()

        val (selfSignedCert, _) = buildDelegatedSignerCert(
            signingKeyPair = attackerKeyPair,
            subjectName = X500Name("CN=Attacker Self-Signed Responder"),
            includeOcspSigningEku = true,
        )

        val ocspDer = buildOcspResponseDer(
            leafCert,
            signerKey = attackerKeyPair.private,
            signerCert = selfSignedCert,
        )

        assertFailsWith<IllegalArgumentException> {
            handler.validate(ocspDer, leafCert.encoded, caCert.encoded)
        }
    }

    private companion object {
        const val OCSP_URL = "http://ocsp.test.example.com"
        const val CRL_URL = "http://crl.test.example.com/ca.crl"
    }
}
