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

import PublicKeyOut
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.crypto.p256UncompressedPointToJwk
import de.gematik.zeta.sdk.crypto.sha256PublicKeyPoint
import derEcdsaToJose

internal class SecureEnclaveClientInstanceKey(
    private val store: SecureEnclaveKeyStore,
    private val tag: String = CLIENT_INSTANCE_KEY_TAG,
) : ClientKeyPoPSigner {

    /**
     * The uncompressed 65-byte P-256 public key point of the Client Instance Key, creating the key if it
     * does not exist yet, or `null` if the Secure Enclave is unavailable / key creation is not permitted.
     */
    fun resolvePublicKeyPoint(): ByteArray? = store.publicKey(tag) ?: store.createKey(tag)

    /** Wraps a resolved [point] together with its JWK representation. */
    fun toPublicKeyOut(point: ByteArray): PublicKeyOut = PublicKeyOut(point, p256UncompressedPointToJwk(point))

    /** Signs the raw JWS signing [input] with the Secure Enclave key, returning a JOSE signature. */
    fun signWithClientKey(input: ByteArray): ByteArray = derEcdsaToJose(store.sign(tag, hashWithSha256(input)), 32)

    /**
     * Explicit Proof of Possession: DER ECDSA signature over SHA-256 of the client public key.
     * No JOSE conversion — returns the raw DER.
     */
    override suspend fun signProofOfPossession(): ByteArray {
        val point = resolvePublicKeyPoint() ?: error("Secure Enclave client key unavailable for tag=$tag")
        return store.sign(tag, sha256PublicKeyPoint(point))
    }

    /** Deletes the Secure Enclave Client Instance Key from the Keychain. No-op if it does not exist. */
    fun delete() = store.deleteKey(tag)
}
