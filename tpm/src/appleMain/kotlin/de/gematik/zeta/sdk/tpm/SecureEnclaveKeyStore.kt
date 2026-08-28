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

/** Stable, fixed Keychain application tag for the Client Instance Key */
internal const val CLIENT_INSTANCE_KEY_TAG: String = "zeta_client_instance_key"

/**
 * Abstraction over the Apple Security-framework operations needed for the Secure-Enclave-backed Client Instance Key.
 */
interface SecureEnclaveKeyStore {
    /** Uncompressed 65-byte P-256 public key point (0x04 || X || Y) for [tag], or null if none. */
    fun publicKey(tag: String): ByteArray?

    /**
     * Create a permanent, Secure-Enclave-backed P-256 key tagged [tag] and return its uncompressed
     * public key point, or null if the Secure Enclave is unavailable / key creation is not permitted.
     */
    fun createKey(tag: String): ByteArray?

    /**
     * Sign a pre-computed 32-byte SHA-256 [digest] with the tagged Secure Enclave key using
     * ECDSA (X9.62, digest variant), returning the DER-encoded signature. Throws if the key is missing.
     */
    fun sign(tag: String, digest: ByteArray): ByteArray

    /** Delete the tagged Secure Enclave key from the Keychain. No-op if absent. */
    fun deleteKey(tag: String)
}
