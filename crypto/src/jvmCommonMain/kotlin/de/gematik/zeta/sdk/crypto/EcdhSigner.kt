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

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

actual class EcdhSigner actual constructor() {
    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    actual fun sign(privateKey: ByteArray, signingInput: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("EC", "BC")
        val key = kf.generatePrivate(PKCS8EncodedKeySpec(privateKey))

        return Signature.getInstance("SHA256withECDSA", "BC").apply {
            initSign(key)
            update(signingInput)
        }.sign()
    }

    actual fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val kf = KeyFactory.getInstance("EC", "BC")
        val pub = kf.generatePublic(X509EncodedKeySpec(publicKey)) as ECPublicKey

        val verifier = Signature.getInstance("SHA256withECDSA", "BC").apply {
            initVerify(pub)
            update(data)
        }

        return verifier.verify(signature)
    }
}
