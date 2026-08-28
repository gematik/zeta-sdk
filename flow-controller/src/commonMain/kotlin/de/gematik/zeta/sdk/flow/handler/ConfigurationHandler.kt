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

package de.gematik.zeta.sdk.flow.handler

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.configuration.ConfigurationApi
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.ServiceDiscoveryException
import de.gematik.zeta.sdk.configuration.WellKnownSchemaValidation
import de.gematik.zeta.sdk.configuration.WellKnownSchemaValidationImpl
import de.gematik.zeta.sdk.configuration.WellKnownTypes
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.network.http.client.hostOf
import de.gematik.zeta.sdk.storage.ResourceScope
import kotlinx.serialization.json.Json

/**
 * Coordinates loading and validation of well-known metadata for a resource.
 * If data is already cached and linked, it returns immediately.
 */
class ConfigurationHandler(
    private val configurationApi: ConfigurationApi,
    private val validator: WellKnownSchemaValidation = WellKnownSchemaValidationImpl(),
) : CapabilityHandler {
    companion object {
        private const val SERVICE_DISCOVERY_ERROR_CODE = "SERVICE_DISCOVERY_ERROR"
    }
    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.ConfigurationFiles

    /**
     * Ensures protected-resource and authorization-server metadata are present and linked.
     * Uses cache first, otherwise fetches, validates, stores, and links.
     */
    override suspend fun handle(
        need: FlowNeed,
        ctx: FlowContext,
    ): CapabilityResult {
        return try {
            Log.i { "[SDK-DISCOVER] getting context" }
            val storage = ctx.configurationStorage

            if (isMetadataAvailable(storage)) {
                Log.i { "[SDK-DISCOVER] metadata is available" }
                return CapabilityResult.Done
            }

            Log.i { "[SDK-DISCOVER] loading protected resource metadata" }
            val protectedResourceMetadata = getProtectedResource(ctx.resourceScope, storage)
            Log.i { "[SDK-DISCOVER] loading auth server metadata" }
            val authorizationMetadata = getAuthorizationMetadata(protectedResourceMetadata.authorizationServers, storage)

            Log.i { "[SDK-DISCOVER] validating scopes" }
            validateScopes(ctx.resourceScope.scopes, authorizationMetadata.scopesSupported)

            Log.i { "[SDK-DISCOVER] linking resource to auth metadata" }
            storage.linkResourceToAuthorizationServer(authorizationMetadata)

            Log.i { "[SDK-DISCOVER] DONE" }
            CapabilityResult.Done
        } catch (e: ServiceDiscoveryException) {
            CapabilityResult.Error(SERVICE_DISCOVERY_ERROR_CODE, e.message.toString(), e.response)
        }
    }

    /**
     * Returns true if both the protected-resource and its auth-server link exist.
     */
    private suspend fun isMetadataAvailable(storage: ConfigurationStorage): Boolean {
        val hasPr = storage.getProtectedResource() != null
        val hasAuthLink = storage.getAuthServer() != null
        return hasPr && hasAuthLink
    }

    /**
     * Loads protected-resource metadata from cache or fetches+validates+saves it.
     */
    private suspend fun getProtectedResource(
        resourceScope: ResourceScope,
        storage: ConfigurationStorage,
    ): ProtectedResourceMetadata {
        Log.i { "[ZETA-SDK] call getProtectedResource from storage: ${resourceScope.storageKey}" }
        storage.getProtectedResource()?.let { return it }

        Log.i { "[ZETA-SDK] call getProtectedResource from api: ${resourceScope.fqdn}" }
        val cachedETag = storage.getProtectedResourceETag()
        val result = configurationApi.fetchResourceMetadata(resourceScope.fqdn, eTag = cachedETag)

        if (result.notModified) {
            Log.i { "[ZETA-SDK] Protected resource metadata not modified (304), refreshing TTL only" }
            storage.touchProtectedResource(maxAgeSeconds = result.maxAgeSeconds)
            return storage.getProtectedResource()
                ?: throw ConfigurationError.ValidationFailed("Protected resource metadata missing after 304 revalidation")
        }

        val body = requireNotNull(result.body) {
            "Missing body for 200 OK discovery response"
        }

        Log.i { "[ZETA-SDK] validate resource metadata" }
        validateOrThrow(WellKnownTypes.RESOURCE_METADATA, body, configurationApi.getResourceSchema())

        Log.i { "[ZETA-SDK] persist json" }
        return storage.saveProtectedResource(body, maxAgeSeconds = result.maxAgeSeconds, eTag = result.eTag)
    }

    /**
     * Picks an authorization server:
     * - if any listed issuer is already cached, reuse it;
     * - otherwise fetch+validate the first issuer.
     */
    private suspend fun getAuthorizationMetadata(
        authServers: List<String>,
        storage: ConfigurationStorage,
    ): AuthorizationServerMetadata {
        if (authServers.isEmpty()) {
            throw ConfigurationError.AuthorizationServerMissing("No authorization_servers listed by protected resource")
        }
        val cachedMatch: AuthorizationServerMetadata? = storage
            .getAuthServers()
            .let { cached -> cached.firstOrNull { meta -> meta.issuer in authServers } }
        if (cachedMatch != null) return cachedMatch

        val authFqdn = authServers.first()
        val cachedETag = storage.getAuthServerETag(hostOf(authFqdn))
        val result = configurationApi.fetchAuthorizationMetadata(authFqdn, eTag = cachedETag)

        if (result.notModified) {
            Log.i { "[ZETA-SDK] Auth server metadata not modified (304), refreshing TTL only" }
            storage.touchAuthServer(hostOf(authFqdn), maxAgeSeconds = result.maxAgeSeconds, eTag = result.eTag)
            return storage.getAuthServer()
                ?: throw ConfigurationError.ValidationFailed("Auth server metadata missing after 304 revalidation")
        }

        val body = requireNotNull(result.body) {
            "Missing body for 200 OK discovery response"
        }

        validateOrThrow(WellKnownTypes.AUTHORIZATION_METADATA, body, configurationApi.getAuthorizationSchema())
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<AuthorizationServerMetadata>(body)

        return storage.saveAuthServer(parsed, maxAgeSeconds = result.maxAgeSeconds, eTag = result.eTag)
    }

    /**
     * Validates JSON against a schema or throws a typed error.
     */
    private suspend fun validateOrThrow(type: WellKnownTypes, json: String, schema: String) {
        val valid = runCatching { validator.validate(json, schema) }.getOrElse { e ->
            Log.i { "[ZETA-SDK] Validation threw for $type. Reason: ${e.message}" }
            throw ConfigurationError.ValidationFailed("Validation threw for $type", e)
        }
        if (!valid) {
            Log.i { "[ZETA-SDK] Failed to validate $type" }
            throw ConfigurationError.ValidationFailed("[ZETA-SDK] Failed to validate $type")
        }
    }

    private fun validateScopes(scopes: List<String>, scopesSupported: List<String>) {
        val missingScopes = scopes subtract scopesSupported
        if (missingScopes.isNotEmpty()) {
            throw ConfigurationError.ScopesNotSupported("Scopes are not supported by server: $missingScopes")
        }
    }

    /** Errors raised while processing configuration metadata. */
    sealed class ConfigurationError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
        class ValidationFailed(message: String, cause: Throwable? = null) : ConfigurationError(message, cause)
        class AuthorizationServerMissing(message: String, cause: Throwable? = null) : ConfigurationError(message, cause)
        class ScopesNotSupported(message: String, cause: Throwable? = null) : ConfigurationError(message, cause)
    }
}
