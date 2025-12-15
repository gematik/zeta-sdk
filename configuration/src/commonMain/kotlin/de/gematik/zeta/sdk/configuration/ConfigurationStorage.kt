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
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.json.Json

/**
 * Public API for reading/writing configuration metadata in storage.
 */
interface ConfigurationStorage {
    /** Returns the protected-resource metadata for the given URL/host, if cached. */
    suspend fun getProtectedResource(resourceUrl: String): ProtectedResourceMetadata?

    /** Saves a protected-resource well-known JSON and returns the parsed model. */
    suspend fun saveProtectedResource(protectedRes: String): ProtectedResourceMetadata

    /** All cached authorization servers (decoded). */
    suspend fun getAuthServers(): List<AuthorizationServerMetadata>

    /** Returns the authorization server linked to the given resource, if any. */
    suspend fun getAuthServer(resource: String): AuthorizationServerMetadata?

    /**
     * Links a resource to an authorization server and stores/updates the AS entry.
     * The link is resource-FQDN -> auth-FQDN.
     */
    suspend fun linkResourceToAuthorizationServer(resource: String, authServerMetadata: AuthorizationServerMetadata)

    suspend fun aslRequired(resource: String): Boolean

    /** Removes all cached metadata. */
    suspend fun clear()
}

/**
 * Default storage implementation backed by [SdkStorage].
 * Uses three maps:
 *  - resource_by_fqdn: resFqdn -> raw PR JSON
 *  - auth_servers_by_fqdn: authFqdn -> raw AS JSON
 *  - resource_to_auth_fqdn: resFqdn -> authFqdn
 */
class ConfigurationStorageImpl(
    private val sdkStorage: SdkStorage,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : ConfigurationStorage {
    private val storage = ExtendedStorage(sdkStorage)

    companion object {
        const val RESOURCE_BY_FQDN_PREFIX = "resource_by_fqdn:" // resource_by_fqdn:pep.com
        const val AUTH_SERVERS_BY_FQDN_PREFIX = "auth_server_by_fqdn:" // auth_server_by_fqdn:auth.pep.com

        const val AUTH_SERVERS_INDEX_KEY = "auth_servers_index"
        const val RESOURCE_INDEX_KEY = "protected_resource_index" // Map<resFqdn, "present">
        const val RESOURCE_TO_AUTH_FQDN_KEY = "resource_to_auth_fqdn" // Map<resFqdn, "present">
        private const val PRESENT_MARKER = "present" // To indicate that the server already has been added
    }

    private fun prKey(resFqdn: String) =
        "$RESOURCE_BY_FQDN_PREFIX$resFqdn"
    private fun asKey(authFqdn: String) =
        "$AUTH_SERVERS_BY_FQDN_PREFIX$authFqdn"

    /** Reads protected-resource metadata for the given URL/host. */
    override suspend fun getProtectedResource(resourceUrl: String): ProtectedResourceMetadata? {
        val resFqdn = hostOf(resourceUrl)
        val key = prKey(resFqdn)
        val raw = storage.get(key) ?: return null
        return runCatching { json.decodeFromString<ProtectedResourceMetadata>(raw) }.getOrNull()
    }

    /** Saves raw PR JSON under its FQDN key and returns the parsed model. */
    override suspend fun saveProtectedResource(protectedRes: String): ProtectedResourceMetadata {
        val parsed = runCatching { json.decodeFromString<ProtectedResourceMetadata>(protectedRes) }
            .getOrElse { e ->
                Log.e { "Failed to parse ProtectedResourceMetadata. Not saving. Reason: ${e.message}" }
                throw e
            }
        val resFqdn = hostOf(parsed.resource)
        storage.put(prKey(resFqdn), protectedRes)

        storage.upsertStringMap(RESOURCE_INDEX_KEY) { index ->
            if (!index.containsKey(resFqdn)) {
                index[resFqdn] = PRESENT_MARKER
            }
        }

        return parsed
    }

    /** Returns all stored authorization servers (decoded). */
    override suspend fun getAuthServers(): List<AuthorizationServerMetadata> {
        val index = storage.getMap(AUTH_SERVERS_INDEX_KEY) ?: return emptyList()

        return index.keys.mapNotNull { authFqdn ->
            val raw = storage.get(asKey(authFqdn)) ?: return@mapNotNull null
            runCatching { json.decodeFromString<AuthorizationServerMetadata>(raw) }.getOrNull()
        }
    }

    /** Resolves resource -> auth FQDN link and returns the AS metadata, if present. */
    override suspend fun getAuthServer(resource: String): AuthorizationServerMetadata? {
        val resFqdn = hostOf(resource)
        val linkMap = storage.getMap(RESOURCE_TO_AUTH_FQDN_KEY) ?: return null
        val authFqdn = linkMap[resFqdn] ?: return null

        val raw = storage.get(asKey(authFqdn)) ?: return null
        return runCatching { json.decodeFromString<AuthorizationServerMetadata>(raw) }.getOrNull()
    }

    /**
     * Stores/updates the AS entry and writes the resource -> auth link.
     * Existing entries are updated in place.
     */
    override suspend fun linkResourceToAuthorizationServer(resource: String, authServerMetadata: AuthorizationServerMetadata) {
        val resFqdn = hostOf(resource)
        val authFqdn = hostOf(authServerMetadata.issuer)
        val asStorageKey = asKey(authFqdn)

        val desired = json.encodeToString(authServerMetadata)
        val current = storage.get(asStorageKey)

        if (current != desired) {
            storage.put(asStorageKey, desired)
        }

        storage.upsertStringMap(RESOURCE_TO_AUTH_FQDN_KEY) { map -> map[resFqdn] = authFqdn }
        storage.upsertStringMap(AUTH_SERVERS_INDEX_KEY) { index ->
            if (!index.containsKey(authFqdn)) {
                index[authFqdn] = PRESENT_MARKER
            }
        }
    }

    override suspend fun aslRequired(resource: String): Boolean {
        val protectedResource = getProtectedResource(resource)
            ?: error("OPR not found for $resource")
        return (protectedResource.zetaAslUse == ZetaAslUse.REQUIRED)
    }

    /** Removes all maps from persistent storage. */
    override suspend fun clear() {
        Log.d { "Clearing all configuration caches and persisted maps" }
        storage.getMap(RESOURCE_INDEX_KEY)
            ?.keys
            ?.forEach { resFqdn ->
                storage.remove(prKey(resFqdn))
            }
        storage.remove(RESOURCE_INDEX_KEY)
        storage.getMap(AUTH_SERVERS_INDEX_KEY)
            ?.keys
            ?.forEach { authFqdn ->
                storage.remove(asKey(authFqdn))
            }
        storage.remove(AUTH_SERVERS_INDEX_KEY)
        storage.remove(RESOURCE_TO_AUTH_FQDN_KEY)
    }
}
