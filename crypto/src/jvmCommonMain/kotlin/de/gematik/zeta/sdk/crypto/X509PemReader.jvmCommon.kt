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

import org.bouncycastle.asn1.isismtt.x509.AdmissionSyntax
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.internal.asn1.isismtt.ISISMTTObjectIdentifiers
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
actual class X509PemReader {

    actual fun loadCertificate(p12File: String, alias: String, password: String): ByteArray {
        val keystoreBytes = FileInputStream(p12File).readBytes()
        return loadCertificateFromBytes(keystoreBytes, alias, password)
    }

    actual fun loadCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(ByteArrayInputStream(data), password.toCharArray())
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        return certificate.encoded
    }

    actual fun loadPrivateKey(p12File: String, alias: String, password: String): ByteArray {
        val data = FileInputStream(p12File).readBytes()
        return loadPrivateKeyFromBytes(data, alias, password)
    }

    actual fun loadPrivateKeyFromBytes(data: ByteArray, alias: String, password: String): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(ByteArrayInputStream(data), password.toCharArray())
        val privateKey = keyStore.getKey(alias, password.toCharArray()) as PrivateKey?
        requireNotNull(privateKey) { "No key with alias '$alias'" }
        return privateKey.encoded
    }

    actual fun getRegistrationNumber(certificateBytes: ByteArray): String? {
        val admission = X509CertificateHolder(certificateBytes)
            .extensions
            .getExtensionParsedValue(ISISMTTObjectIdentifiers.id_isismtt_at_admission)
        val registrationNumber = AdmissionSyntax.getInstance(admission)
            .contentsOfAdmissions[0]
            .professionInfos[0]
            .registrationNumber
        return registrationNumber
    }
}
