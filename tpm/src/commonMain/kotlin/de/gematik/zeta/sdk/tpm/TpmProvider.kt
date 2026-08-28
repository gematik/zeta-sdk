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
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * Provider that can serve a client instance key (long-lived) and DPoP keys.
 */
interface TpmProvider {
    /** True if backed by HW, otherwise false (software). */
    suspend fun isHardwareBacked(): Boolean

    /** Returns the client instance public key. */
    suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut

    /**
     * Returns the DPoP key pair for the given [resource], generating and
     * persisting one if it does not exist yet.
     */
    suspend fun generateDpopKey(): PublicKeyOut

    /** Sign a hashed digest using the client instance private key. */
    suspend fun signWithClientKey(input: ByteArray): ByteArray

    /** Sign a hashed digest using the DPoP private key bound to [resource]. */
    suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray

    /** Load SM-B x509 certificate from a file path. */
    suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray

    /** Load SM-B x509 certificate from raw bytes. */
    suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray

    /** Sign using the SM-B private key loaded from a file path. */
    suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray

    /** Sign using the SM-B private key loaded from raw bytes. */
    suspend fun signWithSmbKeyFromBytes(input: ByteArray, keystoreBytes: ByteArray, alias: String, password: String): ByteArray

    /** Generate a random UUID. */
    suspend fun randomUuid(): Uuid

    /** Extract registration number from an SM-B x509 certificate. */
    suspend fun getRegistrationNumber(certificate: ByteArray): String

    /**
     * Clears the in-memory client key.
     * If [resource] is provided, also deletes the DPoP key for that resource
     * from storage. If null, clears all DPoP state.
     */
    suspend fun forget(resource: String? = null)
}

/** Platform chooses the best default provider (HW if available, otherwise software). */
expect fun platformDefaultProvider(storage: TpmStorage, appAttestSupported: Boolean = false): TpmProvider

/** Singleton facade */
object Tpm {
    @Volatile
    private var provider: TpmProvider? = null

    fun provider(storage: TpmStorage): TpmProvider =
        provider ?: platformDefaultProvider(storage).also { provider = it }
}
