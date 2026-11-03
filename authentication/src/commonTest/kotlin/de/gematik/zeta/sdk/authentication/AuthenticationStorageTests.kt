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

package de.gematik.zeta.sdk.authentication

import de.gematik.zeta.sdk.storage.ExtendedStorage.Companion.hash
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticationStorageImplTest {
    private val resourceScope = ResourceScope("https://example.com", listOf("scope-a"))

    private fun buildSut(
        scope: ResourceScope = resourceScope,
    ): Pair<AuthenticationStorageImpl, InMemoryStorage> {
        val storage = InMemoryStorage()
        return AuthenticationStorageImpl(storage, scope) to storage
    }

    @Test
    fun saveAccessTokens_storesAccessToken() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        sut.saveAccessTokens("access_token", "refresh_token", 9999L, 9999L)

        // Assert
        assertEquals("access_token", sut.getAccessToken())
    }

    @Test
    fun saveAccessTokens_storesRefreshToken() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        sut.saveAccessTokens("access_token", "refresh_token", 9999L, 9999L)

        // Assert
        assertEquals("refresh_token", sut.getRefreshToken())
    }

    @Test
    fun saveAccessTokens_storesExpiration() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        sut.saveAccessTokens("access_token", "refresh_token", 9999L, 9999L)

        // Assert
        assertEquals("9999", sut.getAccessTokenExpiration())
    }

    @Test
    fun saveAccessTokens_overwritesTokens_onSecondCall() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveAccessTokens("old_access", "old_refresh", 1000L, 1000L)

        // Act
        sut.saveAccessTokens("new_access", "new_refresh", 2000L, 2000L)

        // Assert
        assertEquals("new_access", sut.getAccessToken())
        assertEquals("new_refresh", sut.getRefreshToken())
        assertEquals("2000", sut.getAccessTokenExpiration())
    }

    @Test
    fun getAccessToken_returnsNull_whenNotStored() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getAccessToken()

        // Assert
        assertNull(result)
    }

    @Test
    fun getRefreshToken_returnsNull_whenNotStored() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getRefreshToken()

        // Assert
        assertNull(result)
    }

    @Test
    fun getTokenExpiration_returnsNull_whenNotStored() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getAccessTokenExpiration()

        // Assert
        assertNull(result)
    }

    @Test
    fun clear_removesAllTokens() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveAccessTokens("access_1", "refresh_1", 1000L, 1000L)

        // Act
        sut.clear()

        // Assert
        assertNull(sut.getAccessToken())
        assertNull(sut.getRefreshToken())
        assertNull(sut.getAccessTokenExpiration())
    }

    @Test
    fun clear_doesNothing_whenStorageIsEmpty() = runTest {
        // Arrange
        val (sut, storage) = buildSut()

        // Act
        sut.clear()

        // Assert
        assertTrue(storage.map.isEmpty())
    }

    @Test
    fun saveAccessTokens_doesNotExposeRawFqdn_inStorageKeys() = runTest {
        // Arrange
        val (sut, storage) = buildSut()

        // Act
        sut.saveAccessTokens("access_token", "refresh_token", 9999L, 9999L)

        // Assert
        assertTrue(storage.map.none { it.key.contains("example.com") })
    }

    @Test
    fun saveAccessTokens_sameScope_doesNotDuplicateIndex() = runTest {
        // Arrange
        val (sut, storage) = buildSut()

        // Act
        sut.saveAccessTokens("access_1", "refresh_1", 1000L, 1000L)
        sut.saveAccessTokens("access_2", "refresh_2", 2000L, 2000L)

        // Assert
        val namespacedKey = hash("${resourceScope.storageKey}:${AuthenticationStorageImpl.INDEX_KEY}")
        val indexMap = storage.map[namespacedKey]
        assertNotNull(indexMap)
        val entries = Json.decodeFromString<Map<String, String>>(indexMap)
        assertEquals(1, entries.size)
    }

    @Test
    fun saveAccessTokens_clearsStaleRefreshExpiration_whenNewCallOmitsIt() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveAccessTokens("access_1", "refresh_1", 1000L, 1000L)

        // Act
        sut.saveAccessTokens("access_2", "refresh_2", 2000L, null)

        // Assert
        assertNull(sut.getRefreshTokenExpiration())
    }

    @Test
    fun clearAccessToken_removesAccessTokenAndExpiration() = runTest {
        val (sut, _) = buildSut()
        sut.saveAccessTokens("access_1", "refresh_1", 1000L, 2000L)

        sut.clearAccessToken()

        assertNull(sut.getAccessToken())
        assertNull(sut.getAccessTokenExpiration())
    }

    @Test
    fun clearAccessToken_keepsRefreshToken() = runTest {
        val (sut, _) = buildSut()
        sut.saveAccessTokens("access_1", "refresh_1", 1000L, 2000L)

        sut.clearAccessToken()

        assertEquals("refresh_1", sut.getRefreshToken())
    }

    @Test
    fun clearAccessToken_keepsRefreshTokenExpiration() = runTest {
        val (sut, _) = buildSut()
        sut.saveAccessTokens("access_1", "refresh_1", 1000L, 2000L)

        sut.clearAccessToken()

        assertEquals("2000", sut.getRefreshTokenExpiration())
    }
}
