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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.configuration.models.ZetaAslUse
import de.gematik.zeta.sdk.network.http.client.hostOf
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ExtendedStorage.Companion.PRESENT_MARKER
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

interface ConfigurationStorage {
    /** [name] selects a stored protected-resource document; the default is the primary resource. */
    suspend fun getProtectedResource(name: String = DEFAULT_PR_NAME): ProtectedResourceMetadata?
    suspend fun getProtectedResourceETag(name: String = DEFAULT_PR_NAME): String?
    suspend fun saveProtectedResource(protectedRes: String, name: String = DEFAULT_PR_NAME, maxAgeSeconds: Long?, eTag: String? = null): ProtectedResourceMetadata
    suspend fun touchProtectedResource(name: String = DEFAULT_PR_NAME, maxAgeSeconds: Long?, eTag: String? = null)
    suspend fun getAuthServers(): List<AuthorizationServerMetadata>
    suspend fun getAuthServer(): AuthorizationServerMetadata?
    suspend fun getAuthServerETag(authFqdn: String): String?
    suspend fun saveAuthServer(metadata: AuthorizationServerMetadata, maxAgeSeconds: Long?, eTag: String? = null): AuthorizationServerMetadata
    suspend fun touchAuthServer(authFqdn: String, maxAgeSeconds: Long?, eTag: String? = null)
    suspend fun linkResourceToAuthorizationServer(authServerMetadata: AuthorizationServerMetadata)
    suspend fun aslUse(): ZetaAslUse
    suspend fun clear()
    suspend fun invalidateDiscovery()

    companion object {
        /** Storage name of the primary resource's document — keeps the legacy `pr:resource` key. */
        const val DEFAULT_PR_NAME = "resource"
    }
}

class ConfigurationStorageImpl(
    sdkStorage: SdkStorage,
    resourceScope: ResourceScope,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    val clock: Clock = Clock.System,
) : ConfigurationStorage {
    private val storage = ExtendedStorage(sdkStorage, resourceScope)

    companion object {
        const val PR_PREFIX = "pr:"
        const val AS_PREFIX = "as:"
        const val PR_INDEX = "pr_index"
        const val AS_INDEX = "as_index"
        const val RS_TO_AS = "rs_to_as"
        const val DEFAULT_TTL_SECONDS = 24 * 60 * 60L // 24h
    }

    private fun prKey(name: String) = "$PR_PREFIX$name"
    private fun asKey(authFqdn: String) = "$AS_PREFIX$authFqdn"

    override suspend fun getProtectedResource(name: String): ProtectedResourceMetadata? {
        val raw = storage.get(prKey(name))
        if (raw == null) {
            Log.d { "[ZETA-SDK] PR miss (no entry) name=$name key=${prKey(name)}" }
            return null
        }

        val entry = runCatching { json.decodeFromString<CachedEntry<ProtectedResourceMetadata>>(raw) }
            .onFailure { e -> Log.e { "[ZETA-SDK] PR decode failed name=$name: ${e::class.simpleName} - ${e.message}" } }
            .getOrNull() ?: return null

        val now = clock.now().epochSeconds
        val age = now - entry.fetchedAtEpochSeconds
        if (age > entry.ttlSeconds) {
            Log.i { "[ZETA-SDK] PR expired name=$name age=${age}s ttl=${entry.ttlSeconds}s fetchedAt=${entry.fetchedAtEpochSeconds} now=$now" }
            return null
        }

        Log.d { "[ZETA-SDK] PR hit name=$name age=${age}s ttl=${entry.ttlSeconds}s" }
        return entry.data
    }

    override suspend fun getProtectedResourceETag(name: String): String? {
        val raw = storage.get(prKey(name)) ?: return null
        return runCatching { json.decodeFromString<CachedEntry<ProtectedResourceMetadata>>(raw) }.getOrNull()?.eTag
    }

    override suspend fun saveProtectedResource(
        protectedRes: String,
        name: String,
        maxAgeSeconds: Long?,
        eTag: String?,
    ): ProtectedResourceMetadata {
        val parsed = runCatching { json.decodeFromString<ProtectedResourceMetadata>(protectedRes) }
            .getOrElse { e ->
                Log.e { "[ZETA-SDK] Failed to parse ProtectedResourceMetadata name=$name: ${e.message}" }
                throw e
            }

        val ttl = maxAgeSeconds ?: DEFAULT_TTL_SECONDS
        val fetchedAt = clock.now().epochSeconds
        Log.d {
            "[ZETA-SDK] Saving PR name=$name key=${prKey(name)} " +
                "maxAgeFromServer=${maxAgeSeconds ?: "none (Cache-Control absent/unparsed)"} " +
                "effectiveTtl=${ttl}s eTag=${eTag ?: "none"} fetchedAt=$fetchedAt"
        }

        val entry = CachedEntry(data = parsed, fetchedAtEpochSeconds = fetchedAt, ttlSeconds = ttl, eTag = eTag)
        storage.put(prKey(name), json.encodeToString(entry))
        storage.upsertStringMap(PR_INDEX) { it[name] = PRESENT_MARKER }

        return parsed
    }

    override suspend fun touchProtectedResource(
        name: String,
        maxAgeSeconds: Long?,
        eTag: String?,
    ) {
        val raw = storage.get(prKey(name)) ?: return

        val entry = runCatching {
            json.decodeFromString<CachedEntry<ProtectedResourceMetadata>>(raw)
        }.getOrNull() ?: return

        val refreshed = entry.copy(
            fetchedAtEpochSeconds = clock.now().epochSeconds,
            ttlSeconds = maxAgeSeconds ?: DEFAULT_TTL_SECONDS,
            eTag = eTag ?: entry.eTag,
        )

        Log.i {
            "[ZETA-SDK] Touched PR name=$name (304 revalidated) " +
                "newTtl=${refreshed.ttlSeconds}s eTag=${refreshed.eTag}"
        }

        storage.put(
            prKey(name),
            json.encodeToString(refreshed),
        )
    }

    override suspend fun getAuthServers(): List<AuthorizationServerMetadata> {
        val index = storage.getMap(AS_INDEX)
        if (index == null) {
            Log.d { "[ZETA-SDK] AS index empty" }
            return emptyList()
        }

        val now = clock.now().epochSeconds
        Log.d { "[ZETA-SDK] AS index has ${index.keys.size} entries: ${index.keys}" }

        return index.keys.mapNotNull { authFqdn ->
            val raw = storage.get(asKey(authFqdn))
            if (raw == null) {
                Log.d { "[ZETA-SDK] AS miss for $authFqdn (indexed but no data)" }
                return@mapNotNull null
            }
            val entry = runCatching { json.decodeFromString<CachedEntry<AuthorizationServerMetadata>>(raw) }
                .onFailure { e -> Log.e { "[ZETA-SDK] AS decode failed for $authFqdn: ${e.message}" } }
                .getOrNull() ?: return@mapNotNull null

            val age = now - entry.fetchedAtEpochSeconds
            if (age > entry.ttlSeconds) {
                Log.i { "[ZETA-SDK] AS expired for $authFqdn age=${age}s ttl=${entry.ttlSeconds}s — skipping" }
                return@mapNotNull null
            }
            Log.d { "[ZETA-SDK] AS hit for $authFqdn age=${age}s ttl=${entry.ttlSeconds}s" }
            entry.data
        }
    }

    override suspend fun getAuthServerETag(authFqdn: String): String? {
        val raw = storage.get(asKey(authFqdn)) ?: return null
        return runCatching { json.decodeFromString<CachedEntry<AuthorizationServerMetadata>>(raw) }.getOrNull()?.eTag
    }

    override suspend fun saveAuthServer(
        metadata: AuthorizationServerMetadata,
        maxAgeSeconds: Long?,
        eTag: String?,
    ): AuthorizationServerMetadata {
        val authFqdn = hostOf(metadata.issuer)
        val ttl = maxAgeSeconds ?: DEFAULT_TTL_SECONDS
        val fetchedAt = clock.now().epochSeconds
        Log.d {
            "[ZETA-SDK] Saving AS issuer=${metadata.issuer} fqdn=$authFqdn " +
                "maxAgeFromServer=${maxAgeSeconds ?: "none (Cache-Control absent/unparsed)"} " +
                "effectiveTtl=${ttl}s eTag=${eTag ?: "none"} fetchedAt=$fetchedAt"
        }

        val entry = CachedEntry(data = metadata, fetchedAtEpochSeconds = fetchedAt, ttlSeconds = ttl, eTag = eTag)
        storage.put(asKey(authFqdn), json.encodeToString(entry))
        storage.upsertStringMap(AS_INDEX) { it[authFqdn] = PRESENT_MARKER }

        return metadata
    }

    override suspend fun touchAuthServer(
        authFqdn: String,
        maxAgeSeconds: Long?,
        eTag: String?,
    ) {
        val raw = storage.get(asKey(authFqdn)) ?: return

        val entry = runCatching {
            json.decodeFromString<CachedEntry<AuthorizationServerMetadata>>(raw)
        }.getOrNull() ?: return

        val refreshed = entry.copy(
            fetchedAtEpochSeconds = clock.now().epochSeconds,
            ttlSeconds = maxAgeSeconds ?: DEFAULT_TTL_SECONDS,
            eTag = eTag ?: entry.eTag,
        )

        Log.i {
            "[ZETA-SDK] Touched AS fqdn=$authFqdn (304 revalidated) " +
                "newTtl=${refreshed.ttlSeconds}s eTag=${refreshed.eTag}"
        }

        storage.put(
            asKey(authFqdn),
            json.encodeToString(refreshed),
        )
    }

    override suspend fun getAuthServer(): AuthorizationServerMetadata? {
        val authFqdn = storage.getMap(RS_TO_AS)?.get("resource")
        if (authFqdn == null) {
            Log.i { "[ZETA-SDK] getAuthServer: no RS_TO_AS link" }
            return null
        }
        val raw = storage.get(asKey(authFqdn))
        if (raw == null) {
            Log.i { "[ZETA-SDK] getAuthServer: linked fqdn=$authFqdn has no stored data" }
            return null
        }
        val entry = runCatching { json.decodeFromString<CachedEntry<AuthorizationServerMetadata>>(raw) }
            .onFailure { e -> Log.e { "[ZETA-SDK] getAuthServer decode failed fqdn=$authFqdn: ${e.message}" } }
            .getOrNull() ?: return null

        val now = clock.now().epochSeconds
        val age = now - entry.fetchedAtEpochSeconds
        if (age > entry.ttlSeconds) {
            Log.i { "[ZETA-SDK] getAuthServer expired fqdn=$authFqdn age=${age}s ttl=${entry.ttlSeconds}s" }
            return null
        }

        Log.d { "[ZETA-SDK] getAuthServer hit fqdn=$authFqdn age=${age}s ttl=${entry.ttlSeconds}s" }
        return entry.data
    }

    override suspend fun linkResourceToAuthorizationServer(authServerMetadata: AuthorizationServerMetadata) {
        val authFqdn = hostOf(authServerMetadata.issuer)
        val desired = json.encodeToString(authServerMetadata)

        val existingRaw = storage.get(asKey(authFqdn))
        val existingEntry = existingRaw?.let {
            runCatching { json.decodeFromString<CachedEntry<AuthorizationServerMetadata>>(it) }.getOrNull()
        }

        if (existingEntry == null || json.encodeToString(existingEntry.data) != desired) {
            Log.d { "[ZETA-SDK] Persisting AS fqdn=$authFqdn" }
            val entry = CachedEntry(
                data = authServerMetadata,
                fetchedAtEpochSeconds = clock.now().epochSeconds,
                ttlSeconds = DEFAULT_TTL_SECONDS,
            )
            storage.put(asKey(authFqdn), json.encodeToString(entry))
            storage.upsertStringMap(AS_INDEX) { it[authFqdn] = PRESENT_MARKER }
        } else {
            Log.d { "[ZETA-SDK] AS fqdn=$authFqdn unchanged" }
        }

        storage.upsertStringMap(RS_TO_AS) { it["resource"] = authFqdn }
    }

    override suspend fun aslUse(): ZetaAslUse =
        getProtectedResource()?.zetaAslUse ?: error("OPR not found")

    override suspend fun clear() {
        Log.d { "[ZETA-SDK] Clearing all configuration caches" }
        storage.getMap(PR_INDEX)?.keys?.forEach { storage.remove(prKey(it)) }
        // Fallback for stored data written before the index tracked the default entry.
        storage.remove(prKey(ConfigurationStorage.DEFAULT_PR_NAME))
        storage.remove(PR_INDEX)
        storage.getMap(AS_INDEX)?.keys?.forEach { storage.remove(asKey(it)) }
        storage.remove(AS_INDEX)
        storage.remove(RS_TO_AS)
    }

    override suspend fun invalidateDiscovery() {
        Log.d { "[ZETA-SDK] invalidateDiscovery() called" }
        clear()
    }
}

@Serializable
data class CachedEntry<T>(
    val data: T,
    val fetchedAtEpochSeconds: Long,
    val ttlSeconds: Long, // max-age
    val eTag: String? = null,
)
