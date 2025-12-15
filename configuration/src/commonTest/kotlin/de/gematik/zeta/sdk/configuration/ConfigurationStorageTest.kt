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

import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.storage.InMemoryStorage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ConfigurationStorageImpl].
 */
class ConfigurationStorageTest {
    private fun prStorageKeyFor(host: String) =
        ConfigurationStorageImpl.RESOURCE_BY_FQDN_PREFIX + host

    private fun asStorageKeyFor(host: String) =
        ConfigurationStorageImpl.AUTH_SERVERS_BY_FQDN_PREFIX + host

    @Test
    fun getProtectedResource_returnsNull_whenResourceIsMissing() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)

        // Act
        val result = storage.getProtectedResource("https://api.example.com/v1")

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResource_returnsNull_whenDeserializationFails() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)
        val fqdn = "api.example.com"
        sdk.put(prStorageKeyFor(fqdn), "not-a-json")

        // Act
        val out = storage.getProtectedResource("https://$fqdn/v1")

        // Assert
        assertNull(out)
    }

    @Test
    fun getProtectedResource_returnsNull_ifResourceDoesNotExists() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)
        sdk.put(prStorageKeyFor("other-host"), "invalid-json")

        // Act
        val result = storage.getProtectedResource("https://non-existing.example.com")

        // Assert
        assertNull(result)
    }

    @Test
    fun getProtectedResource_returnsCorrectValue() = runTest {
        // Arrange
        val resourceUrl = "https://api.example.com/v1"
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)

        val goodJson = getDummyProtectedResourceObject(resourceUrl, listOf("https://auth.example.com"))
        storage.saveProtectedResource(goodJson)

        // Act
        val result = storage.getProtectedResource(resourceUrl)

        // Assert
        assertNotNull(result)
        assertEquals(resourceUrl, result.resource)
    }

    @Test
    fun linkResourceToAuthorizationServer_createsLink() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)

        val res = "https://api.example.com/v1"
        val asMeta = getDummyAuthServerObject(
            "https://auth.example.com",
            "https://auth.example.com/token",
        )

        // Act
        storage.linkResourceToAuthorizationServer(res, asMeta)

        // Assert
        val asRaw = sdk.get(asStorageKeyFor("auth.example.com"))!!
        val decoded = Json.decodeFromString<AuthorizationServerMetadata>(asRaw)
        assertEquals("https://auth.example.com", decoded.issuer)

        val linkRaw = sdk.get(ConfigurationStorageImpl.RESOURCE_TO_AUTH_FQDN_KEY)!!
        val linkMap = Json.decodeFromString<Map<String, String>>(linkRaw)
        assertEquals("auth.example.com", linkMap["api.example.com"])
    }

    @Test
    fun linkResourceToAuthorizationServer_doesNotDuplicateLinkForSameResource() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)

        val res = "https://api.example.com"
        val metaV1 = getDummyAuthServerObject(
            "https://auth.example.com",
            "https://auth.example.com/token",
        )

        // Act
        storage.linkResourceToAuthorizationServer(res, metaV1)
        val metaSame = metaV1.copy()
        storage.linkResourceToAuthorizationServer(res, metaSame)

        // Assert
        val authServers = storage.getAuthServers()
        assertEquals(1, authServers.size)
    }

    @Test
    fun linkResourceToAuthorizationServer_overwritesAuthorizationServer_forSameResource() = runTest {
        // Arrange
        val res = "https://api.example.com"
        val storage = ConfigurationStorageImpl(InMemoryStorage())
        val metaV1 = getDummyAuthServerObject("https://auth.example.com", "/tokenV1")
        val metaV2 = getDummyAuthServerObject("https://auth.example.com", "/tokenV2")

        // Act & Assert
        storage.linkResourceToAuthorizationServer(res, metaV1)
        assertTrue(storage.getAuthServer(res)!!.tokenEndpoint.endsWith("/tokenV1"))

        storage.linkResourceToAuthorizationServer(res, metaV2)
        assertTrue(storage.getAuthServer(res)!!.tokenEndpoint.endsWith("/tokenV2"))
    }

    @Test
    fun getAuthServer_returnsNull_whenNoResourceFound() = runTest {
        // Arrange
        val res = "https://api.example.com"
        val storage = ConfigurationStorageImpl(InMemoryStorage())
        val metaV1 = getDummyAuthServerObject("https://auth.example.com", "/tokenV1")

        // Act
        storage.linkResourceToAuthorizationServer(res, metaV1)

        // Assert
        assertNull(storage.getAuthServer("https://not-found.example.com"))
    }

    @Test
    fun getAuthServer_returnsNull_whenNoAuthServerFound() = runTest {
        // Arrange
        val res = "https://api.example.com"
        val inMemStorage = InMemoryStorage()
        val storage = ConfigurationStorageImpl(inMemStorage)
        val metaV1 = getDummyAuthServerObject("https://auth.example.com", "/tokenV1")
        storage.linkResourceToAuthorizationServer(res, metaV1)
        inMemStorage.remove(asStorageKeyFor("auth.example.com"))

        // Act
        val result = storage.getAuthServer(res)

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServer_returnsNull_whenAuthServerDataIsCorrupted() = runTest {
        // Arrange
        val res = "https://api.example.com"
        val inMemStorage = InMemoryStorage()
        val storage = ConfigurationStorageImpl(inMemStorage)
        val metaV1 = getDummyAuthServerObject("https://auth.example.com", "/tokenV1")
        storage.linkResourceToAuthorizationServer(res, metaV1)
        inMemStorage.put(asStorageKeyFor("auth.example.com"), "{wrong-value}")

        // Act
        val result = storage.getAuthServer(res)

        // Assert
        assertNull(result)
    }

    @Test
    fun getAuthServers_returnsEmptyList() = runTest {
        // Arrange
        val storage = ConfigurationStorageImpl(InMemoryStorage())

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun getAuthServers_returnsOnlyDataThatCanBeDeserialized() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)

        val index = mapOf(
            "test" to "present",
            "" to "present",
        )
        sdk.put(ConfigurationStorageImpl.AUTH_SERVERS_INDEX_KEY, Json.encodeToString(index))
        sdk.put(asStorageKeyFor("test"), Json.encodeToString(getDummyAuthServerObject()))
        sdk.put(asStorageKeyFor(""), "{invalid-json}")

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertEquals(1, result.size)
    }

    @Test
    fun getAuthServers_returnsListOfLinkedAuthServers() = runTest {
        // Arrange
        val res = "https://api.example.com"
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)
        val asDoc = getDummyAuthServerObject()
        storage.linkResourceToAuthorizationServer(res, asDoc)

        // Act
        val result = storage.getAuthServers()

        // Assert
        assertNotNull(result)
        assertEquals(1, result.size)
    }

    @Test
    fun getAuthServers_doesNotCreateDuplicatesForSameResource() = runTest {
        // Arrange
        val res = "https://api.example.com"
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)
        val asDoc1 = getDummyAuthServerObject()
        val asDoc2 = getDummyAuthServerObject()

        storage.linkResourceToAuthorizationServer(res, asDoc1)
        storage.linkResourceToAuthorizationServer(res, asDoc2)

        // Act
        val out = storage.getAuthServers()

        // Assert
        assertNotNull(out)
        assertEquals(1, out.size)
    }

    @Test
    fun clear_removesCacheAndStorage() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)
        val res = "https://api.example.com"

        val prJson = getDummyProtectedResourceObject(res)
        storage.saveProtectedResource(prJson)

        val asMeta = getDummyAuthServerObject("https://auth.example.com", "/token")
        storage.linkResourceToAuthorizationServer(res, asMeta)

        // Act
        storage.clear()

        assertNull(sdk.get(prStorageKeyFor("api.example.com")))
        assertNull(sdk.get(asStorageKeyFor("auth.example.com")))
        assertNull(sdk.get(ConfigurationStorageImpl.RESOURCE_INDEX_KEY))
        assertNull(sdk.get(ConfigurationStorageImpl.AUTH_SERVERS_INDEX_KEY))
        assertNull(sdk.get(ConfigurationStorageImpl.RESOURCE_TO_AUTH_FQDN_KEY))
        assertNull(storage.getProtectedResource(res))
        assertNull(storage.getAuthServer(res))
        assertTrue(storage.getAuthServers().isEmpty())
    }

    @Test
    fun saveProtectedResource_throwsException_whenInvalidData() = runTest {
        // Arrange
        val sdk = InMemoryStorage()
        val storage = ConfigurationStorageImpl(sdk)

        // Assert
        assertFailsWith<SerializationException> {
            // Act
            storage.saveProtectedResource("{invalid-json}")
        }
    }
}
