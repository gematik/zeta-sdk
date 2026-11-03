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
package de.gematik.zeta.sdk.attestation

import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlin.io.encoding.Base64

/**
 * Attestation state shared by registration and runtime attestation. Backed by [ExtendedStorage] so
 * the keys are hashed and namespaced per resource scope like every other SDK store; cleared by
 * `ZetaSdkClient.forget()`, kept by `clearRegistration()`.
 */
class AttestationStateStorage(storage: SdkStorage, resourceScope: ResourceScope? = null) {
    private val storage = ExtendedStorage(storage, resourceScope)

    companion object {
        const val KEY_TYPE = "attestation.type"
        const val KEY_KEY_ID = "attestation.key_id"
        const val KEY_ZETA_ATTESTATION_TOKEN = "attestation.zeta_attestation_token"
        const val KEY_ATTESTATION_OBJECT = "attestation.object"
        const val KEY_CERTIFICATE_CHAIN = "attestation.certificate_chain"
        private val ALL_KEYS = listOf(KEY_TYPE, KEY_KEY_ID, KEY_ZETA_ATTESTATION_TOKEN, KEY_ATTESTATION_OBJECT, KEY_CERTIFICATE_CHAIN)
    }

    suspend fun getAttestationType(): RegisteredAttestationType? =
        RegisteredAttestationType.fromWireName(storage.get(KEY_TYPE))

    suspend fun setAttestationType(type: RegisteredAttestationType) = storage.put(KEY_TYPE, type.wireName)

    suspend fun getKeyId(): String? = storage.get(KEY_KEY_ID)
    suspend fun setKeyId(keyId: String) = storage.put(KEY_KEY_ID, keyId)

    /** Attestation object of the registration (base64 CBOR), bound to the key id. */
    suspend fun getAttestationObject(): String? = storage.get(KEY_ATTESTATION_OBJECT)
    suspend fun setAttestationObject(attestationObject: String) = storage.put(KEY_ATTESTATION_OBJECT, attestationObject)

    /** Android Key Attestation chain of the registration (DER, leaf first), bound to the key id. */
    suspend fun getCertificateChain(): List<ByteArray>? =
        storage.get(KEY_CERTIFICATE_CHAIN)?.let { chain -> chain.split(',').map { Base64.decode(it) } }

    suspend fun hasCertificateChain(): Boolean = !storage.get(KEY_CERTIFICATE_CHAIN).isNullOrEmpty()

    suspend fun setCertificateChain(chain: List<ByteArray>) {
        require(chain.isNotEmpty()) { "Certificate chain must not be empty" }
        storage.put(KEY_CERTIFICATE_CHAIN, chain.joinToString(",") { Base64.encode(it) })
    }

    suspend fun removeAttestedKey() {
        storage.remove(KEY_KEY_ID)
        storage.remove(KEY_ATTESTATION_OBJECT)
        storage.remove(KEY_CERTIFICATE_CHAIN)
    }

    suspend fun getZetaAttestationToken(): String? = storage.get(KEY_ZETA_ATTESTATION_TOKEN)
    suspend fun setZetaAttestationToken(token: String) = storage.put(KEY_ZETA_ATTESTATION_TOKEN, token)
    suspend fun removeZetaAttestationToken() = storage.remove(KEY_ZETA_ATTESTATION_TOKEN)

    suspend fun clearAll() = ALL_KEYS.forEach { storage.remove(it) }
}
