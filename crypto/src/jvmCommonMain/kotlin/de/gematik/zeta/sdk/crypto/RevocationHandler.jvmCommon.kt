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

import de.gematik.zeta.logging.Log
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.ocsp.OCSPResponseStatus
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.ocsp.BasicOCSPResp
import org.bouncycastle.cert.ocsp.CertificateID
import org.bouncycastle.cert.ocsp.OCSPReqBuilder
import org.bouncycastle.cert.ocsp.OCSPResp
import org.bouncycastle.cert.ocsp.RevokedStatus
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.time.Instant

actual class RevocationHandlerImpl actual constructor() : RevocationHandler {
    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
    private val cf = CertificateFactory.getInstance("X.509", "BC")
    private fun parse(der: ByteArray): X509Certificate =
        cf.generateCertificate(der.inputStream()) as X509Certificate

    actual override fun getOcspValidity(
        ocspResponseDer: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): OcspValidity {
        val basicResp = OCSPResp(ocspResponseDer).responseObject as BasicOCSPResp
        val cert = parse(certDer)

        val singleResp = basicResp.responses
            .firstOrNull { it.certID.serialNumber == cert.serialNumber }
            ?: error("No OCSP single response for certificate serial ${cert.serialNumber}")

        return OcspValidity(
            thisUpdateEpochSeconds = singleResp.thisUpdate.toInstant().epochSecond,
            nextUpdateEpochSeconds = singleResp.nextUpdate?.toInstant()?.epochSecond,
        )
    }

    actual override fun validate(
        ocspResponseDer: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
        now: Instant,
    ) {
        val ocspResp = OCSPResp(ocspResponseDer)
        Log.d { "OCSP: response status: ${ocspResp.status}" }
        require(ocspResp.status == OCSPResponseStatus.SUCCESSFUL) {
            "OCSP response status not successful: ${ocspResp.status}"
        }

        val basicResp = ocspResp.responseObject as BasicOCSPResp
        val cert = parse(certDer)
        val issuer = parse(issuerDer)
        Log.d { "OCSP: Validating cert: ${cert.subjectX500Principal}" }
        Log.d { "OCSP: Issuer: ${issuer.subjectX500Principal}" }

        val signerCert = basicResp.certs
            ?.firstOrNull()
            ?.let { JcaX509CertificateConverter().setProvider("BC").getCertificate(it) }

        val trustedSigner = when {
            signerCert == null -> issuer
            signerCert.subjectX500Principal == issuer.subjectX500Principal -> issuer
            else -> {
                val signedByIssuer = runCatching { signerCert.verify(issuer.publicKey) }.isSuccess
                val hasOcspSigningEku = signerCert.extendedKeyUsage
                    ?.contains(KeyPurposeId.id_kp_OCSPSigning.id) == true

                require(signedByIssuer && hasOcspSigningEku) {
                    "OCSP response signed by unauthorized signer: ${signerCert.subjectX500Principal}"
                }
                signerCert
            }
        }

        require(
            basicResp.isSignatureValid(
                JcaContentVerifierProviderBuilder().setProvider("BC").build(trustedSigner.publicKey),
            ),
        ) { "OCSP response signature invalid" }
        Log.d { "OCSP signature valid" }

        val digestCalcProvider = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val issuerHolder = JcaX509CertificateHolder(issuer)
        val singleResp = basicResp.responses
            .firstOrNull {
                it.certID.matchesIssuer(issuerHolder, digestCalcProvider) && it.certID.serialNumber == cert.serialNumber
            }
            ?: error("OCSP response does not match certificate serial")
        Log.d { "Matched OCSP response for serial: ${cert.serialNumber}" }

        val nowDate = Date(now.toEpochMilliseconds())
        Log.i { "Current update: ${singleResp.thisUpdate}, " + "Next update: ${singleResp.nextUpdate}, now=$nowDate" }
        require(!nowDate.before(singleResp.thisUpdate)) { "OCSP response not yet valid" }
        singleResp.nextUpdate?.let {
            require(!nowDate.after(it)) { "OCSP response expired (nextUpdate=$it)" }
        }

        val revokedStatus = singleResp.certStatus as? RevokedStatus
        if (revokedStatus != null) {
            throw CertificateRevokedException("Certificate is REVOKED since ${revokedStatus.revocationTime}")
        }
        require(singleResp.certStatus == null) {
            "OCSP status for this certificate is not GOOD: ${singleResp.certStatus}"
        }
        Log.i { "OCSP: Certificate status: OK" }
    }

    actual override suspend fun prepareOcspRequest(
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): OcspRequestData {
        val cf = CertificateFactory.getInstance("X.509", "BC")
        val cert = cf.generateCertificate(certDer.inputStream()) as X509Certificate
        val issuer = cf.generateCertificate(issuerDer.inputStream()) as X509Certificate

        val ocspUrl = extractOcspUrl(cert)
            ?: error("Certificate has no OCSP URL in AIA extension")

        Log.d { "Building OCSP request for cert serial: ${cert.serialNumber}" }
        Log.d { "Cert subject: ${cert.subjectX500Principal}" }
        Log.d { "Cert issuer: ${cert.issuerX500Principal}" }
        Log.d { "OCSP URL: $ocspUrl" }

        val digestCalcProvider = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val certHolder = JcaX509CertificateHolder(cert)
        val issuerHolder = JcaX509CertificateHolder(issuer)

        val certId = CertificateID(
            digestCalcProvider[CertificateID.HASH_SHA1],
            issuerHolder,
            certHolder.serialNumber,
        )

        val requestBuilder = OCSPReqBuilder()
        requestBuilder.addRequest(certId)

        val ocspReq = requestBuilder.build()
        val requestDer = ocspReq.encoded

        Log.d { "OCSP request size: ${requestDer.size} bytes" }

        return OcspRequestData(
            url = ocspUrl,
            requestDer = requestDer,
        )
    }

    actual override fun extractCrlUrl(certDer: ByteArray): String? {
        val cf = CertificateFactory.getInstance("X.509", "BC")
        val cert = cf.generateCertificate(certDer.inputStream()) as X509Certificate

        val crlDistPointBytes = cert.getExtensionValue(Extension.cRLDistributionPoints.id)
            ?: return null

        return try {
            val octets = DEROctetString.getInstance(crlDistPointBytes)
            val crlDistPoint = CRLDistPoint.getInstance(octets.octets)

            crlDistPoint.distributionPoints
                .firstNotNullOfOrNull { dp ->
                    val dpn = dp.distributionPoint
                    if (dpn?.type == DistributionPointName.FULL_NAME) {
                        val generalNames = GeneralNames.getInstance(dpn.name)
                        generalNames.names
                            .firstOrNull { it.tagNo == GeneralName.uniformResourceIdentifier }
                            ?.name
                            ?.toString()
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            Log.w { "Failed to extract CRL URL: ${e.message}" }
            null
        }
    }

    actual override fun validateCrl(
        crlDer: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
        now: Instant,
    ) {
        val cf = CertificateFactory.getInstance("X.509", "BC")
        val cert = cf.generateCertificate(certDer.inputStream()) as X509Certificate
        val issuer = cf.generateCertificate(issuerDer.inputStream()) as X509Certificate

        val crl = cf.generateCRL(crlDer.inputStream()) as X509CRL

        Log.i { "CRL thisUpdate: ${crl.thisUpdate}, nextUpdate: ${crl.nextUpdate}" }

        crl.verify(issuer.publicKey)
        Log.i { "CRL signature valid" }

        val nowDate = Date(now.toEpochMilliseconds())
        require(!nowDate.before(crl.thisUpdate)) { "CRL not yet valid" }
        crl.nextUpdate?.let {
            require(!nowDate.after(it)) { "CRL expired (nextUpdate=$it)" }
        }

        val revokedCert = crl.getRevokedCertificate(cert)
        if (revokedCert != null) {
            throw CertificateRevokedException("Certificate is REVOKED since ${revokedCert.revocationDate}")
        }

        Log.i { "Certificate not found in CRL - status OK" }
    }

    actual override fun getCrlValidity(crlDer: ByteArray): CrlValidity {
        val crl = cf.generateCRL(crlDer.inputStream()) as X509CRL
        return CrlValidity(
            thisUpdateEpochSeconds = crl.thisUpdate.toInstant().epochSecond,
            nextUpdateEpochSeconds = crl.nextUpdate?.toInstant()?.epochSecond,
        )
    }

    private fun extractOcspUrl(cert: X509Certificate): String? {
        val aiaBytes = cert.getExtensionValue(Extension.authorityInfoAccess.id)
            ?: return null

        return try {
            val octets = DEROctetString.getInstance(aiaBytes)
            val aia = AuthorityInformationAccess.getInstance(octets.octets)

            aia.accessDescriptions
                .firstOrNull { it.accessMethod == AccessDescription.id_ad_ocsp }
                ?.accessLocation
                ?.name
                ?.toString()
        } catch (e: Exception) {
            Log.w { "Failed to extract OCSP URL: ${e.message}" }
            null
        }
    }
}
