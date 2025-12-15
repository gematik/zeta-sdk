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

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.SecretWithEncapsulation
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

actual class ML768Kem actual constructor() : Kem {
    private val params = MLKEMParameters.ml_kem_768

    actual override fun generateKeys(): KeyPair {
        val kpg = MLKEMKeyPairGenerator()
        kpg.init(MLKEMKeyGenerationParameters(SecureRandom(), params))

        val kp: AsymmetricCipherKeyPair = kpg.generateKeyPair()
        val pub = kp.public as MLKEMPublicKeyParameters
        val priv = kp.private as MLKEMPrivateKeyParameters

        return KeyPair(
            skpi = pub.encoded,
            sec1 = null,
            privateKey = priv.encoded,
        )
    }

    actual override fun encapsulate(peerPublicKey: ByteArray): KemEncapResult {
        val pub = MLKEMPublicKeyParameters(params, peerPublicKey)
        val gen = MLKEMGenerator(SecureRandom())
        val sw = gen.generateEncapsulated(pub) as SecretWithEncapsulation
        val ss = sw.secret
        val ct = sw.encapsulation
        sw.destroy()

        return KemEncapResult(ciphertext = ct, sharedSecret = ss)
    }

    actual override fun decapsulate(privateKeyRaw: ByteArray, ciphertext: ByteArray): ByteArray {
        val pk = MLKEMPrivateKeyParameters(params, privateKeyRaw)
        val ext = MLKEMExtractor(pk)

        return ext.extractSecret(ciphertext)
    }
}
