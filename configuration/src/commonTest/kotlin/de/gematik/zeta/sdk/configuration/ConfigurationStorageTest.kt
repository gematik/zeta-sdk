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

import de.gematik.zeta.sdk.storage.ExtendedStorage.Companion.hash
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import de.gematik.zeta.time.ZetaClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Unit tests for [ConfigurationStorageImpl].
 */

class ConfigurationStorageTest {

    private fun buildStorage(
        clock: ZetaClock = SystemZetaClock,
        sdk: InMemoryStorage = InMemoryStorage(),
        fqdn: String = "https://api.example.com",
        scope: String = "scope-a",
    ): Pair<ConfigurationStorageImpl, InMemoryStorage> {
        val resourceScope = ResourceScope(fqdn, listOf(scope))

        return ConfigurationStorageImpl(
            sdkStorage = sdk,
            resourceScope = resourceScope,
            clock = clock,
        ) to sdk
    }

    @Test
    fun getProtectedResource_returnsNull_whenResourceIsMissing() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getProtectedResource()

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResource_returnsNull_whenDeserializationFails() = runTest {
        // Arrange
        val (storage, sdk) = buildStorage()
        sdk.put(hash("pr:resource"), "not-a-json")

        // Act
        val result = storage.getProtectedResource()

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResource_returnsCorrectValue() = runTest {
        // Arrange
        val (storage, _) = buildStorage(
            fqdn = "https://api.example.com",
        )

        val goodJson = getDummyProtectedResourceObject(
            "https://api.example.com",
            listOf("https://auth.example.com"),
        )

        storage.saveProtectedResource(
            protectedRes = goodJson,
            maxAgeSeconds = 10,
        )

        // Act
        val result = storage.getProtectedResource()

        // Assert
        assertNotNull(result)
        assertEquals(
            "https://api.example.com",
            result.resource,
        )
    }

    @Test
    fun getProtectedResource_returnsNull_whenExpired() = runTest {
        // Arrange
        val clock = TestClock()
        val (storage, _) = buildStorage(clock = clock)

        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
        )

        clock.advanceSeconds(11)

        // Act
        val result = storage.getProtectedResource()

        // Assert
        assertNull(result)
    }

    @Test
    fun saveProtectedResource_usesDefault24HourTtl_whenMaxAgeMissing() = runTest {
        // Arrange
        val clock = TestClock()
        val (storage, _) = buildStorage(clock = clock)

        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = null,
        )

        clock.advanceSeconds(
            ConfigurationStorageImpl.DEFAULT_TTL_SECONDS - 1,
        )

        // Assert
        assertNotNull(storage.getProtectedResource())

        clock.advanceSeconds(10)

        assertNull(storage.getProtectedResource())
    }

    @Test
    fun saveProtectedResource_storesETag() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
            eTag = "\"pr-etag\"",
        )

        // Assert
        assertEquals(
            "\"pr-etag\"",
            storage.getProtectedResourceETag(),
        )
    }

    @Test
    fun getProtectedResourceETag_returnsNull_whenEntryMissing() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getProtectedResourceETag()

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResourceETag_returnsNull_whenEntryCorrupted() = runTest {
        // Arrange
        val (storage, sdk) = buildStorage()

        sdk.put(
            hash("pr:resource"),
            "invalid-json",
        )

        // Act
        val result = storage.getProtectedResourceETag()

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResourceETag_returnsETag_whenEntryExpired() = runTest {
        // Arrange
        val clock = TestClock()
        val (storage, _) = buildStorage(clock = clock)

        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 5,
            eTag = "\"pr-etag\"",
        )

        clock.advanceSeconds(10)

        // Assert
        assertNull(storage.getProtectedResource())
        assertEquals(
            "\"pr-etag\"",
            storage.getProtectedResourceETag(),
        )
    }

    @Test
    fun touchProtectedResource_keepsExistingETag_whenNewETagIsNull() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
            eTag = "\"existing-etag\"",
        )

        // Act
        storage.touchProtectedResource(
            maxAgeSeconds = 20,
            eTag = null,
        )

        // Assert
        assertEquals(
            "\"existing-etag\"",
            storage.getProtectedResourceETag(),
        )
    }

    @Test
    fun touchProtectedResource_doesNothing_whenEntryMissing() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        storage.touchProtectedResource(
            maxAgeSeconds = 10,
            eTag = "\"etag\"",
        )

        // Assert
        assertNull(storage.getProtectedResource())
        assertNull(storage.getProtectedResourceETag())
    }

    @Test
    fun touchProtectedResource_doesNothing_whenEntryIsCorrupted() = runTest {
        // Arrange
        val (storage, sdk) = buildStorage()

        sdk.put(
            hash("pr:resource"),
            "invalid-json",
        )

        // Act
        storage.touchProtectedResource(
            maxAgeSeconds = 10,
            eTag = "\"etag\"",
        )

        // Assert
        assertNull(storage.getProtectedResource())
        assertNull(storage.getProtectedResourceETag())
    }

    @Test
    fun linkResourceToAuthorizationServer_createsLink() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        val asMeta = getDummyAuthServerObject(
            "https://auth.example.com",
            "https://auth.example.com/token",
        )

        // Act
        storage.linkResourceToAuthorizationServer(asMeta)

        // Assert
        val authServer = storage.getAuthServer()

        assertNotNull(authServer)
        assertEquals(
            "https://auth.example.com",
            authServer.issuer,
        )
        assertEquals(
            "https://auth.example.com/token",
            authServer.tokenEndpoint,
        )
    }

    @Test
    fun linkResourceToAuthorizationServer_doesNotDuplicateLinkForSameResource() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        val meta = getDummyAuthServerObject(
            "https://auth.example.com",
            "https://auth.example.com/token",
        )

        // Act
        storage.linkResourceToAuthorizationServer(meta)
        storage.linkResourceToAuthorizationServer(meta.copy())

        // Assert
        assertEquals(
            1,
            storage.getAuthServers().size,
        )
    }

    @Test
    fun linkResourceToAuthorizationServer_overwritesAuthorizationServer_forSameResource() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        val metaV1 = getDummyAuthServerObject(
            "https://auth.example.com",
            "/tokenV1",
        )

        val metaV2 = getDummyAuthServerObject(
            "https://auth.example.com",
            "/tokenV2",
        )

        // Act
        storage.linkResourceToAuthorizationServer(metaV1)

        // Assert
        assertTrue(
            storage.getAuthServer()!!
                .tokenEndpoint
                .endsWith("/tokenV1"),
        )

        // Act
        storage.linkResourceToAuthorizationServer(metaV2)

        // Assert
        assertTrue(
            storage.getAuthServer()!!
                .tokenEndpoint
                .endsWith("/tokenV2"),
        )
    }

    @Test
    fun saveAuthServer_storesETag() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        val metadata = getDummyAuthServerObject(
            "https://auth.example.com",
            "https://auth.example.com/token",
        )

        // Act
        storage.saveAuthServer(
            metadata = metadata,
            maxAgeSeconds = 10,
            eTag = "\"as-etag\"",
        )

        // Assert
        assertEquals(
            "\"as-etag\"",
            storage.getAuthServerETag("auth.example.com"),
        )
    }

    @Test
    fun getAuthServerETag_returnsNull_whenEntryMissing() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getAuthServerETag(
            "auth.example.com",
        )

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServerETag_returnsNull_whenEntryCorrupted() = runTest {
        // Arrange
        val (storage, sdk) = buildStorage()

        sdk.put(
            hash("as:auth.example.com"),
            "invalid-json",
        )

        // Act
        val result = storage.getAuthServerETag(
            "auth.example.com",
        )

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServerETag_returnsETag_whenEntryExpired() = runTest {
        // Arrange
        val clock = TestClock()
        val (storage, _) = buildStorage(clock = clock)

        val metadata = getDummyAuthServerObject(
            "https://auth.example.com",
        )

        storage.saveAuthServer(
            metadata = metadata,
            maxAgeSeconds = 5,
            eTag = "\"as-etag\"",
        )

        clock.advanceSeconds(6)

        // Act / Assert
        assertEquals(
            "\"as-etag\"",
            storage.getAuthServerETag("auth.example.com"),
        )
    }

    @Test
    fun touchAuthServer_keepsExistingETag_whenNewETagIsNull() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        val metadata = getDummyAuthServerObject(
            "https://auth.example.com",
        )

        storage.saveAuthServer(
            metadata = metadata,
            maxAgeSeconds = 10,
            eTag = "\"existing-etag\"",
        )

        // Act
        storage.touchAuthServer(
            authFqdn = "auth.example.com",
            maxAgeSeconds = 20,
            eTag = null,
        )

        // Assert
        assertEquals(
            "\"existing-etag\"",
            storage.getAuthServerETag("auth.example.com"),
        )
    }

    @Test
    fun touchAuthServer_doesNothing_whenEntryMissing() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        storage.touchAuthServer(
            authFqdn = "auth.example.com",
            maxAgeSeconds = 10,
            eTag = "\"etag\"",
        )

        // Assert
        assertNull(
            storage.getAuthServerETag("auth.example.com"),
        )
    }

    @Test
    fun touchAuthServer_doesNothing_whenEntryIsCorrupted() = runTest {
        // Arrange
        val (storage, sdk) = buildStorage()

        sdk.put(
            hash("as:auth.example.com"),
            "invalid-json",
        )

        // Act
        storage.touchAuthServer(
            authFqdn = "auth.example.com",
            maxAgeSeconds = 10,
            eTag = "\"etag\"",
        )

        // Assert
        assertNull(
            storage.getAuthServerETag("auth.example.com"),
        )
    }

    @Test
    fun getAuthServer_returnsNull_whenNoAuthServerFound() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        val meta = getDummyAuthServerObject(
            "https://auth.example.com",
            "/tokenV1",
        )

        storage.linkResourceToAuthorizationServer(meta)
        storage.clear()

        // Act
        val result = storage.getAuthServer()

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServer_returnsNull_whenAuthServerDataIsCorrupted() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getAuthServer()

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServer_returnsNull_whenExpired() = runTest {
        // Arrange
        val clock = TestClock()
        val (storage, _) = buildStorage(clock = clock)

        val metadata = getDummyAuthServerObject(
            "https://auth.example.com",
        )

        storage.saveAuthServer(
            metadata = metadata,
            maxAgeSeconds = 5,
        )

        storage.linkResourceToAuthorizationServer(metadata)

        clock.advanceSeconds(6)

        // Act
        val result = storage.getAuthServer()

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServers_returnsEmptyList() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun getAuthServers_returnsOnlyDataThatCanBeDeserialized() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.linkResourceToAuthorizationServer(
            getDummyAuthServerObject(
                "https://test.example.com",
            ),
        )

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertEquals(1, result.size)
    }

    @Test
    fun getAuthServers_returnsListOfLinkedAuthServers() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.linkResourceToAuthorizationServer(
            getDummyAuthServerObject(),
        )

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertEquals(1, result.size)
    }

    @Test
    fun getAuthServers_doesNotCreateDuplicatesForSameResource() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.linkResourceToAuthorizationServer(
            getDummyAuthServerObject(),
        )

        storage.linkResourceToAuthorizationServer(
            getDummyAuthServerObject(),
        )

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertEquals(1, result.size)
    }

    @Test
    fun getAuthServers_skipsExpiredEntries() = runTest {
        // Arrange
        val clock = TestClock()
        val (storage, _) = buildStorage(clock = clock)

        val metadata = getDummyAuthServerObject(
            "https://auth.example.com",
        )

        storage.saveAuthServer(
            metadata = metadata,
            maxAgeSeconds = 5,
        )

        clock.advanceSeconds(6)

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun clear_removesCacheAndStorage() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.saveProtectedResource(
            getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
        )

        storage.linkResourceToAuthorizationServer(
            getDummyAuthServerObject(
                "https://auth.example.com",
                "/token",
            ),
        )

        // Act
        storage.clear()

        // Assert
        assertNull(storage.getProtectedResource())
        assertNull(storage.getAuthServer())
        assertTrue(storage.getAuthServers().isEmpty())
    }

    @Test
    fun invalidateDiscovery_clearsProtectedResourceAuthServerAndETags() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
            eTag = "\"pr-etag\"",
        )

        val authServer = getDummyAuthServerObject(
            "https://auth.example.com",
        )

        storage.saveAuthServer(
            metadata = authServer,
            maxAgeSeconds = 10,
            eTag = "\"as-etag\"",
        )

        storage.linkResourceToAuthorizationServer(authServer)

        // Act
        storage.invalidateDiscovery()

        // Assert
        assertNull(storage.getProtectedResource())
        assertNull(storage.getProtectedResourceETag())
        assertNull(storage.getAuthServer())

        assertNull(
            storage.getAuthServerETag("auth.example.com"),
        )

        assertTrue(storage.getAuthServers().isEmpty())
    }

    @Test
    fun saveProtectedResource_throwsException_whenInvalidData() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Assert
        assertFailsWith<SerializationException> {
            // Act
            storage.saveProtectedResource(
                "{invalid-json}",
                maxAgeSeconds = 10,
            )
        }
    }

    @Test
    fun saveProtectedResource_namedEntry_roundTrips() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        val nsJson = getDummyProtectedResourceObject(
            "https://api.example.com/ns",
            listOf("https://auth.example.com"),
        )

        // Act
        storage.saveProtectedResource(
            nsJson,
            "notification-service",
            maxAgeSeconds = 10,
        )

        // Assert
        val result = storage.getProtectedResource(
            "notification-service",
        )

        assertNotNull(result)
        assertEquals(
            "https://api.example.com/ns",
            result.resource,
        )
    }

    @Test
    fun namedEntries_doNotInterfere() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.saveProtectedResource(
            getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
        )

        storage.saveProtectedResource(
            getDummyProtectedResourceObject(
                "https://api.example.com/ns",
            ),
            "notification-service",
            maxAgeSeconds = 10,
        )

        // Assert
        assertEquals(
            "https://api.example.com",
            storage.getProtectedResource()?.resource,
        )

        assertEquals(
            "https://api.example.com/ns",
            storage.getProtectedResource(
                "notification-service",
            )?.resource,
        )
    }

    @Test
    fun saveProtectedResource_defaultName_usesLegacyStorageKey() = runTest {
        // Arrange
        val scope = ResourceScope(
            "https://api.example.com",
            listOf("scope-a"),
        )

        val sdk = InMemoryStorage()

        val storage = ConfigurationStorageImpl(
            sdk,
            scope,
            clock = SystemZetaClock,
        )

        // Act
        storage.saveProtectedResource(
            getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
        )

        // Assert
        assertNotNull(
            sdk.map[
                hash(
                    "${scope.storageKey}:pr:resource",
                ),
            ],
        )
    }

    @Test
    fun clear_removesNamedEntries() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.saveProtectedResource(
            getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
        )

        storage.saveProtectedResource(
            getDummyProtectedResourceObject(
                "https://api.example.com/ns",
            ),
            "notification-service",
            maxAgeSeconds = 10,
        )

        // Act
        storage.clear()

        // Assert
        assertNull(storage.getProtectedResource())

        assertNull(
            storage.getProtectedResource(
                "notification-service",
            ),
        )
    }

    @Test
    fun clear_removesDefaultEntry_whenIndexPredatesNamedEntries() = runTest {
        // Arrange
        val scope = ResourceScope(
            "https://api.example.com",
            listOf("scope-a"),
        )

        val sdk = InMemoryStorage()

        val storage = ConfigurationStorageImpl(
            sdk,
            scope,
            clock = SystemZetaClock,
        )

        sdk.put(
            hash(
                "${scope.storageKey}:pr:resource",
            ),
            getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
        )

        // Act
        storage.clear()

        // Assert
        assertNull(storage.getProtectedResource())
    }

    @Test
    fun touchProtectedResource_doesNothing_whenEntryIsMissing() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        // Act
        storage.touchProtectedResource(
            maxAgeSeconds = 10,
            eTag = "\"etag\"",
        )

        // Assert
        assertNull(storage.getProtectedResource())
        assertNull(storage.getProtectedResourceETag())
    }

    @Test
    fun touchProtectedResource_doesNothing_whenCachedEntryIsCorrupted() = runTest {
        // Arrange
        val scope = ResourceScope(
            "https://api.example.com",
            listOf("scope-a"),
        )
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(
            sdkStorage = sdk,
            resourceScope = scope,
            clock = SystemZetaClock,
        )

        sdk.put(
            hash("${scope.storageKey}:pr:resource"),
            "invalid-json",
        )

        // Act
        storage.touchProtectedResource(
            maxAgeSeconds = 10,
            eTag = "\"etag\"",
        )

        // Assert
        assertNull(storage.getProtectedResource())
        assertNull(storage.getProtectedResourceETag())
    }

    @Test
    fun touchProtectedResource_updatesOnlySpecifiedNamedEntry() = runTest {
        // Arrange
        val (storage, _) = buildStorage()

        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com",
            ),
            maxAgeSeconds = 10,
            eTag = "\"default-etag\"",
        )

        storage.saveProtectedResource(
            protectedRes = getDummyProtectedResourceObject(
                "https://api.example.com/notification",
            ),
            name = "notification-service",
            maxAgeSeconds = 10,
            eTag = "\"notification-old\"",
        )

        // Act
        storage.touchProtectedResource(
            name = "notification-service",
            maxAgeSeconds = 20,
            eTag = "\"notification-new\"",
        )

        // Assert
        assertEquals(
            "\"default-etag\"",
            storage.getProtectedResourceETag(),
        )

        assertEquals(
            "\"notification-new\"",
            storage.getProtectedResourceETag("notification-service"),
        )
    }

    private class TestClock(
        private var epochSeconds: Long = 1_000_000L,
    ) : ZetaClock {

        override fun now(): Instant =
            Instant.fromEpochSeconds(epochSeconds)

        fun advanceSeconds(seconds: Long) {
            epochSeconds += seconds
        }
    }
}
