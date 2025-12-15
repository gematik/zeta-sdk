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

import de.gematik.zeta.sdk.crypto.EcdhP256Kem
import de.gematik.zeta.sdk.crypto.EcdhSigner
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SignerTest {
    @Test
    fun verify_succeedsForTheSameMessage() {
        val kp = EcdhP256Kem().generateKeys()
        val msg = "test".encodeToByteArray()

        val s = EcdhSigner()
        val sig = s.sign(kp.privateKey, msg)

        assertTrue(s.verify(kp.skpi, msg, sig))
    }

    @Test
    fun verify_failForDifferentMessage() {
        val kp = EcdhP256Kem().generateKeys()
        val msg1 = "test".encodeToByteArray()
        val msg2 = "testX".encodeToByteArray()

        val s = EcdhSigner()
        val sig = s.sign(kp.privateKey, msg1)

        assertTrue(!s.verify(kp.skpi, msg2, sig))
    }

    @Test
    fun verify_signatureIsRandomizedForTheSameMessage() {
        val kp = EcdhP256Kem().generateKeys()
        val msg = "test".encodeToByteArray()

        val s = EcdhSigner()
        val sig1 = s.sign(kp.privateKey, msg)
        val sig2 = s.sign(kp.privateKey, msg)

        assertNotEquals(sig1.toList(), sig2.toList())
    }
}
