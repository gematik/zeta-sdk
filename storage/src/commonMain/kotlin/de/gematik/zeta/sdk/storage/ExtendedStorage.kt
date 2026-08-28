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

class ExtendedStorage(
    private val storage: SdkStorage,
    private val resourceScope: ResourceScope? = null,
) : SdkStorage {
    companion object {
        private const val HASH_RADIX = 36
        private const val HASH_LENGTH = 8
        const val PRESENT_MARKER = "present"
        fun hash(resourceKey: String): String =
            resourceKey.hashCode()
                .toString(HASH_RADIX)
                .takeLast(HASH_LENGTH)
    }

    private fun ns(key: String) = if (resourceScope != null) "${resourceScope.storageKey}:$key" else key

    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun getMap(key: String): MutableMap<String, String>? {
        val raw = storage.get(hash(ns(key)))?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw).toMutableMap()
        }.onFailure { e ->
            Log.e { "Corrupt map for '$key' in storage. Reason: ${e.message}" }
        }.getOrNull()
    }

    suspend fun putMap(key: String, map: Map<String, String>) =
        storage.put(hash(ns(key)), json.encodeToString<Map<String, String>>(map))

    suspend fun upsertStringMap(key: String, mutate: (MutableMap<String, String>) -> Unit) {
        val m = getMap(key) ?: mutableMapOf()
        mutate(m)
        putMap(key, m)
    }

    suspend fun putIndexed(indexKey: String, entryKey: String, entries: Map<String, String>) {
        val keyHash = hash(ns(entryKey))
        entries.forEach { (prefix, value) -> storage.put("$prefix:$keyHash", value) }
        upsertStringMap(indexKey) { it[keyHash] = keyHash }
    }

    suspend fun getIndexed(entryKey: String, prefix: String): String? =
        storage.get("$prefix:${hash(ns(entryKey))}")

    suspend fun removeIndexed(indexKey: String, entryKey: String, prefixes: List<String>) {
        val keyHash = hash(ns(entryKey))
        prefixes.forEach { prefix -> storage.remove("$prefix:$keyHash") }
        upsertStringMap(indexKey) { it.remove(keyHash) }
    }

    suspend fun clearIndexed(indexKey: String, entryKey: String, prefixes: List<String>) {
        val keyHash = hash(ns(entryKey))
        prefixes.forEach { prefix -> storage.remove("$prefix:$keyHash") }
        remove(indexKey)
    }

    suspend fun clearAllIndexed(indexKey: String, prefixes: List<String>) {
        getMap(indexKey)?.keys?.forEach { keyHash ->
            prefixes.forEach { prefix -> storage.remove("$prefix:$keyHash") }
        }
        remove(indexKey)
    }

    override suspend fun put(key: String, value: String) = storage.put(hash(ns(key)), value)
    override suspend fun get(key: String): String? = storage.get(hash(ns(key)))
    override suspend fun remove(key: String) = storage.remove(hash(ns(key)))
    override suspend fun clear() = storage.clear()
    fun hash(key: String) = ExtendedStorage.hash(key)
}
