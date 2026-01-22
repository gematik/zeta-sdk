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

package de.gematik.zeta.sdk.storage

import de.gematik.zeta.logging.Log
import kotlinx.serialization.json.Json

interface SdkStorage {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
    suspend fun clear()
}

public class ExtendedStorage(private val storage: SdkStorage) {
    companion object {
        private const val HASH_RADIX = 36 // numbers and letters
        private const val HASH_LENGTH = 8 // 36^8
    }
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Loads a String -> String map or returns null if missing/corrupt. */
    suspend fun getMap(key: String): MutableMap<String, String>? {
        val raw = storage.get(key)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw).toMutableMap()
        }.onFailure { e ->
            Log.e { "Corrupt map for '$key' in storage. Reason: ${e.message}" }
        }.getOrNull()
    }

    suspend fun putMap(key: String, map: MutableMap<String, String>) =
        storage.put(key, json.encodeToString<Map<String, String>>(map))

    /** Upserts a map entry under [key]. */
    suspend fun upsertStringMap(
        key: String,
        mutate: (MutableMap<String, String>) -> Unit,
    ) {
        val m = getMap(key) ?: mutableMapOf()
        mutate(m)
        putMap(key, m)
    }

    suspend fun put(key: String, value: String) = storage.put(key, value)
    suspend fun get(key: String): String? = storage.get(key)
    suspend fun remove(key: String) = storage.remove(key)
    suspend fun clear() = storage.clear()

    fun hash(fqdn: String): String {
        return fqdn.hashCode()
            .toString(HASH_RADIX) // chars and numbers
            .takeLast(HASH_LENGTH) // very low collision prob.
    }

    suspend fun getHashes(hashIndexKey: String) =
        storage.get(hashIndexKey)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    suspend fun registerHash(hashIndexKey: String, fqdn: String): String {
        val shortHash = hash(fqdn)

        val map = storage.get(hashIndexKey)
            ?.split(";")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

        if (!map.contains(shortHash)) {
            storage.put(hashIndexKey, (map + shortHash).joinToString(";"))
        }

        return shortHash
    }
}
