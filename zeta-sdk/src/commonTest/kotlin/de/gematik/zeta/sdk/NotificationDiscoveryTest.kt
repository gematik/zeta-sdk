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

import de.gematik.zeta.sdk.configuration.ConfigurationApi
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.ConfigurationStorageImpl
import de.gematik.zeta.sdk.configuration.DiscoveryFetchResult
import de.gematik.zeta.sdk.configuration.WellKnownSchemaValidation
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationDiscoveryTest {
    private val resourceFqdn = "https://fachdienst.example.com"
    private val guardIssuer = "https://auth.example.com"
    private val allScopes = listOf(
        "notification.pusher.read",
        "notification.pusher.write",
        "notification.channel.read",
        "notification.channel.write",
    )

    @Test
    fun happyPath_resolvesAudienceAndScopes_viaWellKnownSubpath() = runTest {
        // Arrange
        val api = FakeConfigurationApi(nsMetadataJson())
        val sut = buildSut(api, buildConfigStorage())

        // Act
        val request = sut.ensureDiscovered()

        // Assert
        assertEquals("https://ns.example.com", request.audience)
        assertEquals(allScopes.toSet(), request.scopes)
        assertEquals("notification-service", api.lastSubpath)
    }

    @Test
    fun happyPath_persistsMetadata_underNamedEntry() = runTest {
        // Arrange
        val storage = buildConfigStorage()
        val sut = buildSut(
            FakeConfigurationApi(nsMetadataJson()),
            storage,
        )

        // Act
        sut.ensureDiscovered()

        // Assert
        assertNotNull(
            storage.getProtectedResource("notification-service"),
        )
    }

    @Test
    fun repeatedCalls_fetchOnlyOnce() = runTest {
        // Arrange
        val api = FakeConfigurationApi(nsMetadataJson())
        val sut = buildSut(api, buildConfigStorage())

        // Act
        sut.ensureDiscovered()
        sut.ensureDiscovered()

        // Assert
        assertEquals(1, api.fetchCount)
    }

    @Test
    fun cachedDocument_skipsFetch() = runTest {
        // Arrange
        val storage = buildConfigStorage()

        storage.saveProtectedResource(
            protectedRes = nsMetadataJson(),
            name = "notification-service",
            maxAgeSeconds = 10,
        )

        val sut = buildSut(
            FakeConfigurationApi(metadataJson = null),
            storage,
        )

        // Act
        val request = sut.ensureDiscovered()

        // Assert
        assertEquals(
            "https://ns.example.com",
            request.audience,
        )
    }

    @Test
    fun expiredCachedDocument_sendsCachedETag() = runTest {
        // Arrange
        val storage = buildConfigStorage()

        storage.saveProtectedResource(
            protectedRes = nsMetadataJson(),
            name = "notification-service",
            maxAgeSeconds = -1,
            eTag = "\"old-etag\"",
        )

        val api = FakeConfigurationApi(
            metadataJson = nsMetadataJson(),
        )

        val sut = buildSut(api, storage)

        // Act
        sut.ensureDiscovered()

        // Assert
        assertEquals(
            "\"old-etag\"",
            api.lastETag,
        )

        assertEquals(
            "notification-service",
            api.lastSubpath,
        )
    }

    @Test
    fun notModified_touchesCachedNotificationMetadata() = runTest {
        // Arrange
        val storage = buildConfigStorage()

        storage.saveProtectedResource(
            protectedRes = nsMetadataJson(),
            name = "notification-service",
            maxAgeSeconds = -1,
            eTag = "\"old-etag\"",
        )

        val api = FakeConfigurationApi(
            metadataJson = null,
            result = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 300,
                eTag = "\"new-etag\"",
                notModified = true,
            ),
        )

        val sut = buildSut(api, storage)

        // Act
        val request = sut.ensureDiscovered()

        // Assert
        assertEquals(
            "https://ns.example.com",
            request.audience,
        )

        assertNotNull(
            storage.getProtectedResource(
                "notification-service",
            ),
        )

        assertEquals(
            "\"new-etag\"",
            storage.getProtectedResourceETag(
                "notification-service",
            ),
        )

        assertEquals(
            "\"old-etag\"",
            api.lastETag,
        )
    }

    @Test
    fun notModified_throws_whenCachedNotificationMetadataIsMissing() = runTest {
        // Arrange
        val api = FakeConfigurationApi(
            metadataJson = null,
            result = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 300,
                eTag = "\"etag\"",
                notModified = true,
            ),
        )

        val sut = buildSut(
            api = api,
            storage = buildConfigStorage(),
        )

        // Act / Assert
        val error = assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }

        assertTrue(
            error.message.orEmpty().contains(
                "Notification Service metadata missing after 304 revalidation",
            ),
        )
    }

    @Test
    fun responseWithoutBody_throws_whenNotModifiedIsFalse() = runTest {
        // Arrange
        val api = FakeConfigurationApi(
            metadataJson = null,
            result = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 10,
                eTag = "\"etag\"",
                notModified = false,
            ),
        )

        val sut = buildSut(
            api = api,
            storage = buildConfigStorage(),
        )

        // Act / Assert
        val error = assertFailsWith<IllegalArgumentException> {
            sut.ensureDiscovered()
        }

        assertTrue(
            error.message.orEmpty().contains(
                "Missing body for 200 OK discovery response",
            ),
        )
    }

    @Test
    fun successfulFetch_persistsETag() = runTest {
        // Arrange
        val storage = buildConfigStorage()

        val api = FakeConfigurationApi(
            metadataJson = nsMetadataJson(),
            result = DiscoveryFetchResult(
                body = nsMetadataJson(),
                maxAgeSeconds = 120,
                eTag = "\"notification-etag\"",
                notModified = false,
            ),
        )

        val sut = buildSut(api, storage)

        // Act
        sut.ensureDiscovered()

        // Assert
        assertEquals(
            "\"notification-etag\"",
            storage.getProtectedResourceETag(
                "notification-service",
            ),
        )
    }

    @Test
    fun missingLinkedAuthServer_throws() = runTest {
        // Arrange
        val sut = buildSut(
            FakeConfigurationApi(nsMetadataJson()),
            buildConfigStorage(linkGuardAs = false),
        )

        // Act / Assert
        assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }
    }

    @Test
    fun authServerLink_matchesByHost_whenIssuerCarriesRealmPath() = runTest {
        // Arrange
        val storage = ConfigurationStorageImpl(
            InMemoryStorage(),
            ResourceScope(
                resourceFqdn,
                listOf("scope-a"),
            ),
            clock = SystemZetaClock,
        )

        storage.linkResourceToAuthorizationServer(
            guardAuthServer().copy(
                issuer = "$guardIssuer/auth/realms/zeta-guard",
            ),
        )

        val sut = buildSut(
            FakeConfigurationApi(
                nsMetadataJson(
                    authorizationServers = listOf(
                        "$guardIssuer/",
                    ),
                ),
            ),
            storage,
        )

        // Act
        val request = sut.ensureDiscovered()

        // Assert
        assertEquals(
            "https://ns.example.com",
            request.audience,
        )
    }

    @Test
    fun authServerLinkMismatch_throws() = runTest {
        // Arrange
        val json = nsMetadataJson(
            authorizationServers = listOf(
                "https://other.example.com",
            ),
        )

        val sut = buildSut(
            FakeConfigurationApi(json),
            buildConfigStorage(),
        )

        // Act / Assert
        assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }
    }

    @Test
    fun dpopBindingNotRequired_throws() = runTest {
        // Arrange
        val json = nsMetadataJson(
            dpopRequired = false,
        )

        val sut = buildSut(
            FakeConfigurationApi(json),
            buildConfigStorage(),
        )

        // Act / Assert
        assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }
    }

    @Test
    fun onlyNotificationScopesFromSupported_areRequested() = runTest {
        // Arrange
        val json = nsMetadataJson(
            scopesSupported = allScopes +
                listOf(
                    "notification.history.read",
                    "other.scope",
                ),
        )

        val sut = buildSut(
            FakeConfigurationApi(json),
            buildConfigStorage(),
        )

        // Act
        val request = sut.ensureDiscovered()

        // Assert
        assertEquals(
            (allScopes + "notification.history.read").toSet(),
            request.scopes,
        )
    }

    @Test
    fun noNotificationScopesAdvertised_throws() = runTest {
        // Arrange
        val json = nsMetadataJson(
            scopesSupported = listOf(
                "other.scope",
            ),
        )

        val sut = buildSut(
            FakeConfigurationApi(json),
            buildConfigStorage(),
        )

        // Act / Assert
        assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }
    }

    @Test
    fun scopesSupportedAbsent_throws() = runTest {
        // Arrange
        val json = nsMetadataJson(
            scopesSupported = null,
        )

        val sut = buildSut(
            FakeConfigurationApi(json),
            buildConfigStorage(),
        )

        // Act / Assert
        assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }
    }

    @Test
    fun schemaValidationFailure_throws() = runTest {
        // Arrange
        val sut = buildSut(
            FakeConfigurationApi(
                nsMetadataJson(),
            ),
            buildConfigStorage(),
            validator = WellKnownSchemaValidation { _, _ ->
                false
            },
        )

        // Act / Assert
        assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }
    }

    @Test
    fun schemaValidationException_isWrappedInNotificationDiscoveryException() = runTest {
        // Arrange
        val sut = buildSut(
            api = FakeConfigurationApi(
                nsMetadataJson(),
            ),
            storage = buildConfigStorage(),
            validator = WellKnownSchemaValidation { _, _ ->
                error("boom")
            },
        )

        // Act / Assert
        val error = assertFailsWith<NotificationDiscoveryException> {
            sut.ensureDiscovered()
        }

        assertTrue(
            error.message.orEmpty().contains(
                "Notification Service metadata validation errored: boom",
            ),
        )
    }

    // ---- fixtures ----

    private class FakeConfigurationApi(
        private val metadataJson: String?,
        private val result: DiscoveryFetchResult? = null,
    ) : ConfigurationApi {

        var fetchCount = 0
        var lastSubpath: String? = null
        var lastETag: String? = null

        override suspend fun fetchResourceMetadata(
            resourceUrl: String,
            subpath: String?,
            eTag: String?,
        ): DiscoveryFetchResult {
            fetchCount++
            lastSubpath = subpath
            lastETag = eTag

            return result ?: DiscoveryFetchResult(
                body = metadataJson
                    ?: error(
                        "fetch must not run for this test",
                    ),
                maxAgeSeconds = 10,
                eTag = "test",
                notModified = false,
            )
        }

        override suspend fun fetchAuthorizationMetadata(
            authFqdns: String,
            eTag: String?,
        ): DiscoveryFetchResult =
            error("not in scope of the test")

        override suspend fun getResourceSchema(): String =
            "{}"

        override suspend fun getAuthorizationSchema(): String =
            error("not in scope of the test")
    }

    private fun buildSut(
        api: ConfigurationApi,
        storage: ConfigurationStorage,
        config: NotificationConfig = NotificationConfig(),
        validator: WellKnownSchemaValidation =
            WellKnownSchemaValidation { _, _ -> true },
    ) = NotificationDiscovery(
        api,
        storage,
        config,
        resourceFqdn,
        validator,
    )

    private suspend fun buildConfigStorage(
        linkGuardAs: Boolean = true,
    ): ConfigurationStorage {
        val storage = ConfigurationStorageImpl(
            InMemoryStorage(),
            ResourceScope(
                resourceFqdn,
                listOf("scope-a"),
            ),
            clock = SystemZetaClock,
        )

        if (linkGuardAs) {
            storage.linkResourceToAuthorizationServer(
                guardAuthServer(),
            )
        }
        return storage
    }

    private fun nsMetadataJson(
        resource: String = "https://ns.example.com",
        authorizationServers: List<String> = listOf(guardIssuer),
        scopesSupported: List<String>? = allScopes,
        dpopRequired: Boolean = true,
    ): String = """
        {
          "resource": "$resource",
          "authorization_servers": [
            ${authorizationServers.joinToString(",") { "\"$it\"" }}
          ],
          "zeta_asl_use": "not_supported",
          ${
        scopesSupported?.let {
            """"scopes_supported": [${it.joinToString(",") { s -> "\"$s\"" }}],"""
        }.orEmpty()
    }
          "dpop_bound_access_tokens_required": $dpopRequired
        }
    """.trimIndent()

    private fun guardAuthServer(): AuthorizationServerMetadata = AuthorizationServerMetadata(
        issuer = guardIssuer,
        authorizationEndpoint = "",
        tokenEndpoint = "$guardIssuer/token",
        nonceEndpoint = "$guardIssuer/nonce",
        openidProvidersEndpoint = "",
        jwksUri = "",
        scopesSupported = listOf(""),
        responseTypesSupported = listOf("TOKEN"),
        responseModesSupported = listOf(""),
        grantTypesSupported = listOf(""),
        tokenEndpointAuthMethodsSupported = listOf(""),
        tokenEndpointAuthSigningAlgValuesSupported = listOf(""),
        serviceDocumentation = "",
        uiLocalesSupported = listOf(""),
        codeChallengeMethodsSupported = listOf(""),
        registrationEndpoint = "",
    )
}
