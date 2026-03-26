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
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
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
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate

actual class OcspHandler actual constructor() {
    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
    private val cf = CertificateFactory.getInstance("X.509", "BC")
    private fun parse(der: ByteArray): X509Certificate =
        cf.generateCertificate(der.inputStream()) as X509Certificate

    actual fun getProducedAtEpochSeconds(ocspResponseDer: ByteArray): Long {
        val basicResp = OCSPResp(ocspResponseDer).responseObject as BasicOCSPResp
        return basicResp.producedAt.toInstant().epochSecond
    }
    actual fun validate(
        ocspResponseDer: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ) {
        val ocspResp = OCSPResp(ocspResponseDer)
        Log.i { "OCSP: response status: ${ocspResp.status}" }
        require(ocspResp.status == OCSPResponseStatus.SUCCESSFUL) {
            "OCSP response status not successful: ${ocspResp.status}"
        }

        val basicResp = ocspResp.responseObject as BasicOCSPResp
        val cert = parse(certDer)
        val issuer = parse(issuerDer)
        Log.i { "OCSP: Validating cert: ${cert.subjectX500Principal}" }
        Log.i { "OCSP: Issuer: ${issuer.subjectX500Principal}" }

        val signerCert = basicResp.certs
            ?.firstOrNull()
            ?.let { JcaX509CertificateConverter().setProvider("BC").getCertificate(it) }
            ?: issuer
        Log.i { "OCSP signer cert: ${signerCert.subjectX500Principal}" }

        require(
            basicResp.isSignatureValid(
                JcaContentVerifierProviderBuilder()
                    .setProvider("BC")
                    .build(signerCert.publicKey),
            ),
        ) { "OCSP response signature invalid" }
        Log.i { "OCSP signature valid" }

        val digestCalcProvider = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val issuerHolder = JcaX509CertificateHolder(issuer)
        val singleResp = basicResp.responses
            .firstOrNull {
                it.certID.matchesIssuer(issuerHolder, digestCalcProvider)
                it.certID.serialNumber == cert.serialNumber
            }
            ?: error("OCSP response does not match certificate serial")
        Log.i { "Matched OCSP response for serial: ${cert.serialNumber}" }

        val now = java.util.Date()
        Log.i { "Current update: ${singleResp.thisUpdate}, Next update : ${singleResp.nextUpdate}, now=$now" }
        require(!now.before(singleResp.thisUpdate)) { "OCSP response not yet valid" }
        singleResp.nextUpdate?.let {
            require(!now.after(it)) { "OCSP response expired (nextUpdate=$it)" }
        }

        require(singleResp.certStatus == null) {
            val revoked = singleResp.certStatus as? RevokedStatus
            "Certificate is REVOKED since ${revoked?.revocationTime}"
        }
        Log.i { "OCSP: Certificate status: OK" }
    }

    actual suspend fun prepareOcspRequest(
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): OcspRequestData {
        val cf = CertificateFactory.getInstance("X.509", "BC")
        val cert = cf.generateCertificate(certDer.inputStream()) as X509Certificate
        val issuer = cf.generateCertificate(issuerDer.inputStream()) as X509Certificate

        val ocspUrl = extractOcspUrl(cert)
            ?: error("Certificate has no OCSP URL in AIA extension")

        Log.i { "Building OCSP request for cert serial: ${cert.serialNumber}" }
        Log.i { "Cert subject: ${cert.subjectX500Principal}" }
        Log.i { "Cert issuer: ${cert.issuerX500Principal}" }
        Log.i { "OCSP URL: $ocspUrl" }

        val digestCalcProvider = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
        val certHolder = JcaX509CertificateHolder(cert)
        val issuerHolder = JcaX509CertificateHolder(issuer)

        val certId = CertificateID(
            digestCalcProvider.get(CertificateID.HASH_SHA1),
            issuerHolder,
            certHolder.serialNumber,
        )

        val requestBuilder = OCSPReqBuilder()
        requestBuilder.addRequest(certId)

        val ocspReq = requestBuilder.build()
        val requestDer = ocspReq.encoded

        Log.i { "OCSP request size: ${requestDer.size} bytes" }
        Log.i { "OCSP request hex (first 64 bytes): ${requestDer.take(64).joinToString("") { "%02x".format(it) }}" }

        return OcspRequestData(
            url = ocspUrl,
            requestDer = requestDer,
        )
    }

    actual fun extractCrlUrl(certDer: ByteArray): String? {
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

    actual fun validateCrl(
        crlDer: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ) {
        val cf = CertificateFactory.getInstance("X.509", "BC")
        val cert = cf.generateCertificate(certDer.inputStream()) as X509Certificate
        val issuer = cf.generateCertificate(issuerDer.inputStream()) as X509Certificate

        val crl = cf.generateCRL(crlDer.inputStream()) as X509CRL

        Log.i { "CRL thisUpdate: ${crl.thisUpdate}, nextUpdate: ${crl.nextUpdate}" }

        crl.verify(issuer.publicKey)
        Log.i { "CRL signature valid" }

        val now = java.util.Date()
        require(!now.before(crl.thisUpdate)) { "CRL not yet valid" }
        crl.nextUpdate?.let {
            require(!now.after(it)) { "CRL expired (nextUpdate=$it)" }
        }

        val revokedCert = crl.getRevokedCertificate(cert)
        if (revokedCert != null) {
            error("Certificate is REVOKED since ${revokedCert.revocationDate}")
        }

        Log.i { "Certificate not found in CRL - status OK" }
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
