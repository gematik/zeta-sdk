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
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlin.time.Clock

interface TpmStorage {
    suspend fun saveClientKeys(publicKey: String, privateKey: String)
    suspend fun saveDpopKeys(publicKey: String, privateKey: String)
    suspend fun getClientPublicKey(): String?
    suspend fun getClientPrivateKey(): String?
    suspend fun getDpopPublicKey(): String?
    suspend fun getDpopPrivateKey(): String?
    suspend fun getClientKeyCreatedAt(): String?
    suspend fun deleteDpopKeys()
    suspend fun deleteAllDpopKeys()
    suspend fun clear()
}

class TpmStorageImpl(
    storage: SdkStorage,
    resourceScope: ResourceScope,
) : TpmStorage {
    private val extended = ExtendedStorage(storage, resourceScope)

    private companion object {
        const val ENTRY_KEY = "tpm"
        const val PREFIX_CLIENT_PUBLIC = "client_public_key"
        const val PREFIX_CLIENT_PRIVATE = "client_private_key"
        const val PREFIX_CLIENT_TS = "client_key_ts"
        const val PREFIX_DPOP_PUBLIC = "dpop_public_key"
        const val PREFIX_DPOP_PRIVATE = "dpop_private_key"
        const val TPM_INDEX_KEY = "tpm_key_index"
    }

    override suspend fun saveClientKeys(publicKey: String, privateKey: String) {
        Log.d { "Saving client keys" }
        extended.putIndexed(
            TPM_INDEX_KEY, ENTRY_KEY,
            mapOf(
                PREFIX_CLIENT_PUBLIC to publicKey,
                PREFIX_CLIENT_PRIVATE to privateKey,
                PREFIX_CLIENT_TS to Clock.System.now().toString(),
            ),
        )
    }

    override suspend fun getClientPublicKey(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_CLIENT_PUBLIC)
    override suspend fun getClientPrivateKey(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_CLIENT_PRIVATE)
    override suspend fun getClientKeyCreatedAt(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_CLIENT_TS)

    override suspend fun saveDpopKeys(publicKey: String, privateKey: String) {
        Log.d { "Saving DPoP keys" }
        extended.putIndexed(
            TPM_INDEX_KEY, ENTRY_KEY,
            mapOf(
                PREFIX_DPOP_PUBLIC to publicKey,
                PREFIX_DPOP_PRIVATE to privateKey,
            ),
        )
    }

    override suspend fun getDpopPublicKey(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_DPOP_PUBLIC)
    override suspend fun getDpopPrivateKey(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_DPOP_PRIVATE)

    override suspend fun deleteDpopKeys() {
        Log.d { "Deleting DPoP keys" }
        extended.removeIndexed(TPM_INDEX_KEY, ENTRY_KEY, listOf(PREFIX_DPOP_PUBLIC, PREFIX_DPOP_PRIVATE))
    }

    override suspend fun deleteAllDpopKeys() {
        Log.d { "Deleting all DPoP keys" }
        extended.clearIndexed(TPM_INDEX_KEY, ENTRY_KEY, listOf(PREFIX_DPOP_PUBLIC, PREFIX_DPOP_PRIVATE))
    }

    override suspend fun clear() {
        Log.d { "Clearing all TPM storage" }
        extended.clearIndexed(
            TPM_INDEX_KEY,
            ENTRY_KEY,
            listOf(PREFIX_CLIENT_PUBLIC, PREFIX_CLIENT_PRIVATE, PREFIX_CLIENT_TS, PREFIX_DPOP_PUBLIC, PREFIX_DPOP_PRIVATE),
        )
    }
}
