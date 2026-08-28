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

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.isismtt.ISISMTTObjectIdentifiers
import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

actual class X509CertValidator actual constructor() {

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val cf = CertificateFactory.getInstance("X.509", "BC")

    private fun parse(der: ByteArray): X509Certificate =
        cf.generateCertificate(der.inputStream()) as X509Certificate

    actual fun checkValidity(certDer: ByteArray) {
        parse(certDer).checkValidity()
    }

    actual fun getProfessionOids(certDer: ByteArray): List<String> {
        val holder = X509CertificateHolder(certDer)
        val ext = holder.extensions?.getExtension(ISISMTTObjectIdentifiers.id_isismtt_at_admission)
            ?: return emptyList()

        val admission = AdmissionSyntax.getInstance(
            ASN1Sequence.getInstance(ext.parsedValue),
        )

        return admission.contentsOfAdmissions
            .flatMap { it.professionInfos.toList() }
            .flatMap { it.professionOIDs?.map { oid -> oid.id } ?: emptyList() }
    }
    actual fun getPublicKey(certDer: ByteArray): ByteArray =
        parse(certDer).publicKey.encoded

    actual fun validateCertChain(
        chainDer: List<ByteArray>,
        trustAnchorsDer: List<ByteArray>,
    ) {
        require(chainDer.isNotEmpty()) { "Certificate chain must not be empty" }

        val anchors = trustAnchorsDer
            .map { parse(it) }
            .map { TrustAnchor(it, null) }
            .toSet()

        val params = PKIXParameters(anchors).apply {
            isRevocationEnabled = false
            date = Date()
        }

        val certPath = cf.generateCertPath(chainDer.map { parse(it) })
        CertPathValidator.getInstance("PKIX", "BC").validate(certPath, params)
    }

    actual fun getSanDnsNames(certDer: ByteArray): List<String> = parse(certDer).subjectAlternativeNames
        ?.filter { it[0] == GeneralName.dNSName }
        ?.map { it[1] as String }
        ?: emptyList()
}
