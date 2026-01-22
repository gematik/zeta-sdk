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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.hostOf
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.json.Json

public interface AslStorage {
    public suspend fun saveSession(fqdn: String, session: EstablishedSession)
    public suspend fun getCurrentSession(fqdn: String): EstablishedSession?
    public suspend fun clear(fqdn: String)
    public suspend fun clear()
}

public class AslStorageImpl(
    private val storage: SdkStorage,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },

) : AslStorage {
    private val extendedStorage = ExtendedStorage(storage)
    private companion object {
        const val ASL_SESSION_BY_FQDN = "asl_session_by_fqdn"
        const val ASL_HASH_INDEX_KEY = "asl_hash_index_key"
    }

    private fun aslSessionKey(fqdn: String) = "$ASL_SESSION_BY_FQDN${hostOf(fqdn)}"

    override suspend fun saveSession(fqdn: String, session: EstablishedSession) {
        val hash = extendedStorage.registerHash(ASL_HASH_INDEX_KEY, fqdn)
        Log.d { "Registered hash for ASL session $hash" }

        val parsed = json.encodeToString(session)
        val sessionKey = aslSessionKey(hash)
        Log.d { "Saving ASL session $sessionKey" }

        storage.put(sessionKey, parsed)
    }

    override suspend fun getCurrentSession(fqdn: String): EstablishedSession? {
        val sessionKey = extendedStorage.hash(fqdn)
        Log.d { "Restoring ASL session $sessionKey" }

        val value = storage.get(aslSessionKey(sessionKey))

        if (value.isNullOrEmpty()) return null

        return runCatching { json.decodeFromString<EstablishedSession>(value) }
            .getOrElse { e ->
                Log.e { "Failed to parse EstablishedSession. Not saving. Reason: ${e.message}" }
                throw e
            }
    }

    override suspend fun clear(fqdn: String) {
        Log.d { "Removing ASL session for $fqdn" }
        val sessionKey = aslSessionKey(extendedStorage.hash(fqdn))

        Log.d { "Removing current ASL session $sessionKey from storage" }
        extendedStorage.remove(sessionKey)
    }

    override suspend fun clear() {
        Log.d { "Removing all ASL sessions" }

        extendedStorage.getHashes(ASL_HASH_INDEX_KEY)
            .forEach { hash ->
                val sessionKey = aslSessionKey(hash)
                Log.d { "Removing current ASL session $sessionKey from storage" }

                storage.remove(sessionKey)
            }

        storage.remove(ASL_HASH_INDEX_KEY)
    }
}
