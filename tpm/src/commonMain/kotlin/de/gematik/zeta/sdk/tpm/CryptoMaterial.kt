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

package de.gematik.zeta.sdk.tpm

import de.gematik.zeta.logging.Log
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** Hash algorithms for the zeta project. */
enum class HashAlg { SHA256, }

/** Platform chooses the best default provider (HW if available, otherwise software). */
internal expect fun platformDefaultProvider(): CryptoProvider

/** Singleton facade */
object Tpm {
    @Volatile
    private var provider: CryptoProvider? = null

    fun configure(p: CryptoProvider) {
        provider = p
    }

    public fun provider(): CryptoProvider =
        provider ?: platformDefaultProvider().also { provider = it }
}

suspend fun getClientInstanceKey(): ByteArray {
    Log.d { "Getting client instance public key" }
    return Tpm.provider().generateClientInstanceKey()
}
suspend fun getClientInstanceKeyBase64(): String {
    Log.d { "Getting client instance public key as Base64" }
    return Base64.encode(getClientInstanceKey())
}

// RFC 4122 UUID v4
public fun randomUUID(): ByteArray = Uuid.random().toByteArray()

suspend fun createLongLiveClientKey(): ByteArray {
    Log.d { "Generate DPoP" }
    return Tpm.provider().generateDpopKey()
}

// TODO: Move this logic to attestation
// Calculate attestation challenge
suspend fun calculateAttestationChallenge(nonce: ByteArray, publicKey: ByteArray): ByteArray {
    // Calculate attestation challenge = SHA256(SHA256(pubKey) || nonce)
    Log.d { "Hashing the public key" }
    val publicKeyHash = Tpm.provider().hash(publicKey, HashAlg.SHA256)

    Log.d { "Calculation of the attestation challenge" }
    val attestationChallenge = ByteArray(publicKeyHash.size + nonce.size)
    publicKeyHash.copyInto(attestationChallenge, destinationOffset = 0)
    nonce.copyInto(attestationChallenge, destinationOffset = publicKeyHash.size)

    Log.d { "Hashing the attestation challenge" }
    return Tpm.provider().hash(attestationChallenge, HashAlg.SHA256)
}
