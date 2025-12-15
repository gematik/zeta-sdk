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
    val isHardwareBacked: Boolean

    /** Returns the client instance public key. */
    suspend fun generateClientInstanceKey(): PublicKeyOut

    /** Create a new DPoP key pair and return the public key. */
    suspend fun generateDpopKey(): PublicKeyOut

    /** Sign a hashed digest using the client key private behind. */
    suspend fun signWithClientKey(input: ByteArray): ByteArray

    /** Sign a hashed digest using the client key of DPoP behind. */
    suspend fun signWithDpopKey(input: ByteArray): ByteArray

    /** Get SM-B x509 certificate */
    suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray

    /** Sign a hashed digest using the SM-B key private behind. */
    suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray

    /** Generate random UUID */
    suspend fun randomUuid(): Uuid

    /** Get registration number from SM-B x509 certificate */
    suspend fun getRegistrationNumber(certificate: ByteArray): String

    /** Sets to null the client and the DPoP keys. */
    fun forget()
}

/** Platform chooses the best default provider (HW if available, otherwise software). */
internal expect fun platformDefaultProvider(storage: TpmStorage): TpmProvider

/** Singleton facade */
object Tpm {
    @Volatile
    private var provider: TpmProvider? = null

    fun configure(p: TpmProvider) {
        provider = p
    }

    public fun provider(storage: TpmStorage): TpmProvider =
        provider ?: platformDefaultProvider(storage).also { provider = it }
}
