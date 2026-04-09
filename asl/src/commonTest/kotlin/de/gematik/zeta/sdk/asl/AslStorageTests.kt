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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AslStorageImplTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun saveSession_storesSerializedSession_validSession() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val fqdn = "https://api.example.com/resource"
        val session = buildSession(requestCounter = 42L, encCounter = 13L)

        // Act
        sut.saveSession(fqdn, session)

        // Assert
        val stored = storage.getAll()
        val sessionKey = stored.keys.first { it.startsWith("asl_session_by_fqdn") }
        val storedJson = stored[sessionKey]
        assertNotNull(storedJson)
        val decoded = json.decodeFromString<EstablishedSession>(storedJson)
        assertEquals(42L, decoded.requestCounter)
        assertEquals(13L, decoded.encCounter)
    }

    @Test
    fun saveSession_usesHashedKey_forSessionStorage() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val fqdn = "https://api.example.com/resource"
        val session = buildSession()

        // Act
        sut.saveSession(fqdn, session)

        // Assert
        val stored = storage.getAll()
        val sessionKeys = stored.keys.filter { it.startsWith("asl_session_by_fqdn") }
        assertEquals(1, sessionKeys.size)
        val sessionKey = sessionKeys.first()
        assertFalse(sessionKey.contains("example.com"))
    }

    @Test
    fun getCurrentSession_returnsSession_validStoredSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val fqdn = "https://api.example.com/resource"
        val session = buildSession(requestCounter = 99L, encCounter = 77L)
        sut.saveSession(fqdn, session)

        // Act
        val result = sut.getCurrentSession(fqdn)

        // Assert
        assertEquals(99L, result!!.requestCounter)
        assertEquals(77L, result.encCounter)
    }

    @Test
    fun getCurrentSession_returnsNull_noStoredSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val fqdn = "https://api.example.com/resource"

        // Act
        val result = sut.getCurrentSession(fqdn)

        // Assert
        assertNull(result)
    }

    @Test
    fun getCurrentSession_returnsNull_emptyStoredValue() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        storage.put("asl_hash_index_key", "hash123")
        storage.put("asl_session_by_fqdnhash123", "")

        val (sut, _) = buildSut(storage)
        val fqdn = "https://api.example.com/resource"

        // Act
        val result = sut.getCurrentSession(fqdn)

        // Assert
        assertNull(result)
    }

    @Test
    fun getCurrentSession_throwsException_invalidJson() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        val fqdn = "https://api.example.com/resource"

        val (sut1, _) = buildSut(storage)
        sut1.saveSession(fqdn, buildSession())

        val storedKeys = storage.getAll().keys.first { it.startsWith("asl_session_by_fqdn") }
        storage.put(storedKeys, "{invalid json}")
        val (sut2, _) = buildSut(storage)

        // Act & Assert
        assertFailsWith<Exception> {
            sut2.getCurrentSession(fqdn)
        }
    }

    @Test
    fun clear_removesSession_validFqdn() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val fqdn = "https://api.example.com/resource"
        val session = buildSession()
        sut.saveSession(fqdn, session)

        // Act
        sut.clear(fqdn)

        // Assert
        val result = sut.getCurrentSession(fqdn)
        assertNull(result)
    }

    @Test
    fun clear_doesNotRemoveOtherSessions_multipleSessions() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val fqdn1 = "https://api1.example.com/resource"
        val fqdn2 = "https://api2.example.com/resource"

        sut.saveSession(fqdn1, buildSession(requestCounter = 1L))
        sut.saveSession(fqdn2, buildSession(requestCounter = 2L))

        // Act
        sut.clear(fqdn1)

        // Assert
        val result1 = sut.getCurrentSession(fqdn1)
        val result2 = sut.getCurrentSession(fqdn2)
        assertNull(result1)
        assertEquals(2L, result2!!.requestCounter)
    }

    @Test
    fun clear_removesAllSessions_multipleSessions() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val fqdn1 = "https://api1.example.com/resource"
        val fqdn2 = "https://api2.example.com/resource"
        val fqdn3 = "https://api3.example.com/resource"
        sut.saveSession(fqdn1, buildSession(requestCounter = 1L))
        sut.saveSession(fqdn2, buildSession(requestCounter = 2L))
        sut.saveSession(fqdn3, buildSession(requestCounter = 3L))

        // Act
        sut.clear()

        // Assert
        val stored = storage.getAll()
        val sessionKeys = stored.keys.filter { it.startsWith("asl_session_by_fqdn") }
        assertTrue(sessionKeys.isEmpty())
        assertFalse(stored.containsKey("asl_hash_index_key"))
    }

    @Test
    fun clear_removesHashIndexKey_afterClearingAll() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val fqdn = "https://api.example.com/resource"
        sut.saveSession(fqdn, buildSession())

        // Act
        sut.clear()

        // Assert
        val hashIndex = storage.getAll()["asl_hash_index_key"]
        assertNull(hashIndex)
    }

    @Test
    fun clear_handlesEmptyHashList_noSessions() = runTest {
        // Arrange
        val (sut, storage) = buildSut()

        // Act
        sut.clear()

        // Assert
        val stored = storage.getAll()
        assertTrue(stored.isEmpty())
    }

    @Test
    fun clear_removesOnlyAslSessions_mixedStorage() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        storage.put("other_key_1", "value1")
        storage.put("other_key_2", "value2")

        val (sut, _) = buildSut(storage)
        val fqdn = "https://api.example.com/resource"
        sut.saveSession(fqdn, buildSession())

        // Act
        sut.clear()

        // Assert
        val stored = storage.getAll()
        assertEquals(2, stored.size)
        assertTrue(stored.containsKey("other_key_1"))
        assertTrue(stored.containsKey("other_key_2"))
    }

    private class FakeSdkStorage : SdkStorage {
        private val store = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = store[key]

        override suspend fun put(key: String, value: String) {
            store[key] = value
        }

        override suspend fun remove(key: String) {
            store.remove(key)
        }

        override suspend fun clear() {
            TODO()
        }

        fun getAll(): Map<String, String> = store.toMap()
    }

    private fun buildSut(storage: SdkStorage = FakeSdkStorage()): Pair<AslStorageImpl, FakeSdkStorage> {
        val fakeStorage = storage as FakeSdkStorage
        return AslStorageImpl(fakeStorage, json) to fakeStorage
    }
}

private fun assertTrue(condition: Boolean) {
    kotlin.test.assertTrue(condition)
}

private fun assertFalse(condition: Boolean) {
    kotlin.test.assertFalse(condition)
}

private fun buildSession(
    keyId: ByteArray = ByteArray(32) { it.toByte() },
    requestCounter: Long = 0L,
    encCounter: Long = 0L,
): EstablishedSession = EstablishedSession(
    keyId = keyId,
    c2sAppDataKey = ByteArray(32) { 1 },
    s2cAppDataKey = ByteArray(32) { 2 },
    requestCounter = requestCounter,
    encCounter = encCounter,
)
