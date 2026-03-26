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

import de.gematik.zeta.sdk.storage.InMemoryStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticationStorageImplTest {

    private fun buildSut(): Pair<AuthenticationStorageImpl, InMemoryStorage> {
        val storage = InMemoryStorage()
        return AuthenticationStorageImpl(storage) to storage
    }

    @Test
    fun saveAccessTokens_storesAccessToken() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        sut.saveAccessTokens("https://example.com", "access_token", "refresh_token", 9999L)

        // Assert
        val result = sut.getAccessToken("https://example.com")
        assertEquals("access_token", result)
    }

    @Test
    fun saveAccessTokens_storesRefreshToken() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        sut.saveAccessTokens("https://example.com", "access_token", "refresh_token", 9999L)

        // Assert
        val result = sut.getRefreshToken("https://example.com")
        assertEquals("refresh_token", result)
    }

    @Test
    fun saveAccessTokens_storesExpiration() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        sut.saveAccessTokens("https://example.com", "access_token", "refresh_token", 9999L)

        // Assert
        val result = sut.getTokenExpiration("https://example.com")
        assertEquals("9999", result)
    }

    @Test
    fun saveAccessTokens_overwritesTokens_onSecondCall() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveAccessTokens("https://example.com", "old_access", "old_refresh", 1000L)

        // Act
        sut.saveAccessTokens("https://example.com", "new_access", "new_refresh", 2000L)

        // Assert
        assertEquals("new_access", sut.getAccessToken("https://example.com"))
        assertEquals("new_refresh", sut.getRefreshToken("https://example.com"))
        assertEquals("2000", sut.getTokenExpiration("https://example.com"))
    }

    @Test
    fun saveAccessTokens_storesTokensForMultipleFqdns() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        sut.saveAccessTokens("https://first.com", "access_1", "refresh_1", 1000L)
        sut.saveAccessTokens("https://second.com", "access_2", "refresh_2", 2000L)

        // Assert
        assertEquals("access_1", sut.getAccessToken("https://first.com"))
        assertEquals("access_2", sut.getAccessToken("https://second.com"))
    }

    @Test
    fun getAccessToken_returnsNull_whenNotStored() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getAccessToken("https://unknown.com")

        // Assert
        assertNull(result)
    }

    @Test
    fun getRefreshToken_returnsNull_whenNotStored() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getRefreshToken("https://unknown.com")

        // Assert
        assertNull(result)
    }

    @Test
    fun getTokenExpiration_returnsNull_whenNotStored() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getTokenExpiration("https://unknown.com")

        // Assert
        assertNull(result)
    }

    @Test
    fun getAccessToken_returnsNull_forDifferentFqdn() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveAccessTokens("https://example.com", "access_token", "refresh_token", 9999L)

        // Act
        val result = sut.getAccessToken("https://other.com")

        // Assert
        assertNull(result)
    }

    @Test
    fun clear_removesAllTokens() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveAccessTokens("https://first.com", "access_1", "refresh_1", 1000L)
        sut.saveAccessTokens("https://second.com", "access_2", "refresh_2", 2000L)

        // Act
        sut.clear()

        // Assert
        assertNull(sut.getAccessToken("https://first.com"))
        assertNull(sut.getAccessToken("https://second.com"))
        assertNull(sut.getRefreshToken("https://first.com"))
        assertNull(sut.getRefreshToken("https://second.com"))
    }

    @Test
    fun clear_removesHashIndex() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        sut.saveAccessTokens("https://example.com", "access_token", "refresh_token", 9999L)

        // Act
        sut.clear()

        // Assert
        assertNull(storage.map["hash_index"])
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
    fun saveAccessTokens_usesHashedKeys_notRawFqdn() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val fqdn = "https://example.com"

        // Act
        sut.saveAccessTokens(fqdn, "access_token", "refresh_token", 9999L)

        // Assert
        assertTrue(storage.map.none { it.key.contains(fqdn) })
    }

    @Test
    fun saveAccessTokens_sameFqdn_doesNotDuplicateHashIndex() = runTest {
        // Arrange
        val (sut, storage) = buildSut()

        // Act
        sut.saveAccessTokens("https://example.com", "access_1", "refresh_1", 1000L)
        sut.saveAccessTokens("https://example.com", "access_2", "refresh_2", 2000L)

        // Assert
        val hashIndex = storage.map["hash_index"] ?: ""
        val hashes = hashIndex.split(";").filter { it.isNotBlank() }
        assertEquals(1, hashes.size)
    }
}
