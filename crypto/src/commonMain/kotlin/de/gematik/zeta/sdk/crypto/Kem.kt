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

import Jwk
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.Serializable

interface Kem {
    fun generateKeys(): KeyPair
    fun encapsulate(peerPublicKey: ByteArray): KemEncapResult
    fun decapsulate(privateKeyRaw: ByteArray, ciphertext: ByteArray): ByteArray
}

expect class EcdhP256Kem() : Kem {
    fun toJwk(publicKey: ByteArray): Jwk
    override fun generateKeys(): KeyPair
    override fun encapsulate(peerPublicKey: ByteArray): KemEncapResult
    override fun decapsulate(privateKeyRaw: ByteArray, ciphertext: ByteArray): ByteArray
    fun loadKeys(priv: ByteArray, pub: ByteArray): KeyPair
}
expect class ML768Kem() : Kem {
    override fun generateKeys(): KeyPair
    override fun encapsulate(peerPublicKey: ByteArray): KemEncapResult
    override fun decapsulate(privateKeyRaw: ByteArray, ciphertext: ByteArray): ByteArray
}

@Serializable
data class KeyPair(
    val skpi: ByteArray,
    val sec1: ByteArray? = null,
    val privateKey: ByteArray,
)

data class KemEncapResult(
    // ECDH: ephemeral pubkey (SEC1 uncompressed). ML-KEM: KEM ciphertext
    val ciphertext: ByteArray,
    // Raw KEM/EC shared secret (pre-HKDF)
    val sharedSecret: ByteArray,
)

@Serializable
data class EcPointP256(
    val crv: String,
    val x: ByteArray,
    val y: ByteArray,
)

fun ECDH.PublicKey.toSec1Uncompressed(): ByteArray {
    val sec1 = encodeToByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed)
    require(sec1.size == 65 && sec1[0] == 0x04.toByte()) { "Invalid P-256 public key" }
    val x = sec1.copyOfRange(1, 33)
    val y = sec1.copyOfRange(33, 65)

    return byteArrayOf(0x04) + x + y
}

fun hashWithSha256(input: ByteArray): ByteArray =
    CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(input)
