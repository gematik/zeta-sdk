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
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERPrintableString
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

actual class X509PemReader {

    actual fun loadCertificate(p12File: String, alias: String, password: String): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(FileInputStream(p12File), password.toCharArray())
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        return certificate.encoded
    }

    actual fun loadPrivateKey(p12File: String, alias: String, password: String): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(FileInputStream(p12File), password.toCharArray())
        val privateKey = keyStore.getKey(alias, password.toCharArray()) as PrivateKey?
        requireNotNull(privateKey) { "No key with alias '$alias'" }
        return privateKey.encoded
    }

    actual fun getRegistrationNumber(certificateBytes: ByteArray): String? {
        val factory = CertificateFactory.getInstance("X.509")
        val certificate = factory.generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
        val ext = certificate.getExtensionValue("1.3.36.8.3.3")
        val der = ASN1Primitive.fromByteArray((ASN1Primitive.fromByteArray(ext) as ASN1OctetString).octets)
        val admissionSeq = ASN1Sequence.getInstance(der)
        return findRegistrationNumberRecursive(admissionSeq, null, "1.2.276.0.76.4.50")
    }

    private fun findRegistrationNumberRecursive(node: ASN1Sequence, parent: ASN1Sequence?, targetOid: String): String? {
        val elements = node.objects.toList()
        val hasTargetOid = elements.any {
            it is ASN1ObjectIdentifier && it.id == targetOid
        }

        if (hasTargetOid) {
            val printable = parent?.objects?.toList().orEmpty().firstOrNull { it is DERPrintableString } as? DERPrintableString
            if (printable != null) {
                return printable.string
            }
        }
        for (child in elements) {
            if (child is ASN1Sequence) {
                val found = findRegistrationNumberRecursive(child, node, targetOid)
                if (found != null) return found
            }
        }
        return null
    }
}
