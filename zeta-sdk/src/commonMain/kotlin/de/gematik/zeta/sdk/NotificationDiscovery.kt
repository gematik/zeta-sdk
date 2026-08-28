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

package de.gematik.zeta.sdk

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.configuration.ConfigurationApi
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.WellKnownSchemaValidation
import de.gematik.zeta.sdk.configuration.WellKnownSchemaValidationImpl
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.network.http.client.hostOf
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.notifications.NotificationTokenRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A Notification Service metadata document failed to load or violates the design constraints (§4.3). */
class NotificationDiscoveryException(message: String) : Exception(message)

/**
 * Lazy discovery of the Notification Service metadata (RFC 9728 well-known subpath, A_28436).
 *
 * Resolves the [NotificationTokenRequest] (NS resource id as audience + requested scopes) for the
 * token provider. The document is cached persistently under a named protected-resource
 * entry and the resolution is memoized per instance; validation (§4.3) runs on every fresh
 * resolution:
 * - the NS must list the already-linked guard Authorization Server — no second AS discovery,
 * - DPoP-bound access tokens must be required (A_29979),
 * - at least one `notification.*` scope must be advertised in `scopes_supported`.
 *
 * Scope selection is internal to the SDK: all advertised `notification.*` scopes are requested.
 */
internal class NotificationDiscovery(
    private val configurationApi: ConfigurationApi,
    private val configurationStorage: ConfigurationStorage,
    private val config: NotificationConfig,
    private val resourceFqdn: String,
    private val validator: WellKnownSchemaValidation = WellKnownSchemaValidationImpl(),
) {
    private val mutex = Mutex()
    private var resolved: NotificationTokenRequest? = null

    suspend fun ensureDiscovered(): NotificationTokenRequest = mutex.withLock {
        resolved?.let { return it }

        val metadata = configurationStorage.getProtectedResource(PR_ENTRY_NAME) ?: fetchAndPersist()

        validateAuthServerLink(metadata)
        if (!metadata.dpopBoundAccessTokensRequired) {
            throw NotificationDiscoveryException(
                "Notification Service metadata must require DPoP-bound access tokens (A_29979)",
            )
        }

        val requested = metadata.scopesSupported.orEmpty().filter { it.startsWith("notification.") }.toSet()
        if (requested.isEmpty()) {
            throw NotificationDiscoveryException(
                "No notification scopes advertised in the Notification Service metadata",
            )
        }

        NotificationTokenRequest(audience = metadata.resource, scopes = requested).also { resolved = it }
    }

    private suspend fun fetchAndPersist(): ProtectedResourceMetadata {
        Log.i { "[ZETA-SDK] discovering Notification Service metadata (subpath=${config.wellKnownSubpath})" }

        val cachedETag = configurationStorage.getProtectedResourceETag(PR_ENTRY_NAME)
        val result = configurationApi.fetchResourceMetadata(resourceFqdn, config.wellKnownSubpath, eTag = cachedETag)

        if (result.notModified) {
            Log.i { "[ZETA-SDK] Notification Service metadata not modified (304), refreshing TTL only" }
            configurationStorage.touchProtectedResource(PR_ENTRY_NAME, maxAgeSeconds = result.maxAgeSeconds, eTag = result.eTag)
            return configurationStorage.getProtectedResource(PR_ENTRY_NAME)
                ?: throw NotificationDiscoveryException("Notification Service metadata missing after 304 revalidation")
        }

        val body = requireNotNull(result.body) {
            "Missing body for 200 OK discovery response"
        }

        val valid = runCatching { validator.validate(body, configurationApi.getResourceSchema()) }
            .getOrElse { e ->
                throw NotificationDiscoveryException("Notification Service metadata validation errored: ${e.message}")
            }
        if (!valid) {
            throw NotificationDiscoveryException("Notification Service metadata failed schema validation")
        }

        return configurationStorage.saveProtectedResource(
            body,
            PR_ENTRY_NAME,
            maxAgeSeconds = result.maxAgeSeconds,
            eTag = result.eTag,
        )
    }

    private suspend fun validateAuthServerLink(metadata: ProtectedResourceMetadata) {
        val authServer = configurationStorage.getAuthServer()
            ?: throw NotificationDiscoveryException(
                "No linked guard Authorization Server - primary discovery must run before Notification Service use",
            )
        // Compare by host: deployments list the AS discovery host in authorization_servers while
        // the linked issuer carries the realm path — the same host-level resolution the primary
        // flow applies when linking the resource to its Authorization Server.
        val linkedHost = hostOf(authServer.issuer)
        if (metadata.authorizationServers.none { hostOf(it) == linkedHost }) {
            throw NotificationDiscoveryException(
                "Notification Service metadata does not list the linked guard Authorization Server (${authServer.issuer})",
            )
        }
    }

    companion object {
        /** Name of the NS document's protected-resource storage entry (`pr:notification-service`). */
        const val PR_ENTRY_NAME = "notification-service"
    }
}
