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
import de.gematik.zeta.sdk.authentication.AuthConfig
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
import kotlinx.serialization.json.Json

/**
 * Coordinates loading and validation of well-known metadata for a resource.
 * If data is already cached and linked, it returns immediately.
 */
class ConfigurationHandler(
    private val configurationApi: ConfigurationApi,
    private val authConfig: AuthConfig,
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
            val storage = ctx.configurationStorage

            if (isMetadataAvailable(ctx.resource, storage)) {
                return CapabilityResult.Done
            }

            val protectedResourceMetadata = getProtectedResource(ctx.resource, storage)
            val authorizationMetadata = getAuthorizationMetadata(protectedResourceMetadata.authorizationServers, storage)

            validateScopes(authConfig.scopes, authorizationMetadata.scopesSupported)

            storage.linkResourceToAuthorizationServer(ctx.resource, authorizationMetadata)

            CapabilityResult.Done
        } catch (e: ServiceDiscoveryException) {
            CapabilityResult.Error(SERVICE_DISCOVERY_ERROR_CODE, e.message.toString(), e.response)
        }
    }

    /**
     * Returns true if both the protected-resource and its auth-server link exist.
     */
    private suspend fun isMetadataAvailable(resource: String, storage: ConfigurationStorage): Boolean {
        val hasPr = storage.getProtectedResource(resource) != null
        val hasAuthLink = storage.getAuthServer(resource) != null
        return hasPr && hasAuthLink
    }

    /**
     * Loads protected-resource metadata from cache or fetches+validates+saves it.
     */
    private suspend fun getProtectedResource(
        resourceUrl: String,
        storage: ConfigurationStorage,
    ): ProtectedResourceMetadata {
        storage.getProtectedResource(resourceUrl)?.let { return it }

        val prJson = configurationApi.fetchResourceMetadata(resourceUrl)
        // validateOrThrow(WellKnownTypes.RESOURCE_METADATA, prJson, configurationApi.getResourceSchema())

        return storage.saveProtectedResource(prJson)
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
            .let { cached ->
                cached.firstOrNull { meta -> meta.issuer in authServers }
            }
        if (cachedMatch != null) return cachedMatch

        val asJson = configurationApi.fetchAuthorizationMetadata(authServers.first())
        // validateOrThrow(WellKnownTypes.AUTHORIZATION_METADATA, asJson, configurationApi.getAuthorizationSchema())

        return Json.decodeFromString(asJson)
    }

    /**
     * Validates JSON against a schema or throws a typed error.
     */
    private suspend fun validateOrThrow(type: WellKnownTypes, json: String, schema: String) {
        val valid = runCatching { validator.validate(json, schema) }.getOrElse { e ->
            Log.d { "Validation threw for $type. Reason: ${e.message}" }
            throw ConfigurationError.ValidationFailed("Validation threw for $type", e)
        }
        if (!valid) {
            Log.d { "Failed to validate $type" }
            throw ConfigurationError.ValidationFailed("Failed to validate $type")
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
