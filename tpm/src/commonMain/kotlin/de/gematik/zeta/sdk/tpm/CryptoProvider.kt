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

/**
 * Provider that can serve a client instance key (long-lived) and DPoP keys.
 */
interface CryptoProvider {
    /** True if backed by HW, otherwise false (software). */
    val isHardwareBacked: Boolean

    /** Returns the client instance public key. */
    suspend fun generateClientInstanceKey(): ByteArray

    /** Create a new DPoP key pair and return the public key. */
    suspend fun generateDpopKey(): ByteArray

    /** Sign a hashed digest using the client key private behind. */
    suspend fun signDigestWithClientKey(digest: ByteArray): ByteArray

    /** Sign a hashed digest using the client key of DPoP behind. */
    suspend fun signDigestWithDpopKey(digest: ByteArray): ByteArray

    /** Hash helper */
    fun hash(input: ByteArray, alg: HashAlg = HashAlg.SHA256): ByteArray
}
