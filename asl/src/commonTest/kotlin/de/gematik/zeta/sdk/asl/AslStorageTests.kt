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

import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AslStorageImplTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val resourceScope = ResourceScope("https://api.example.com/resource", listOf("scope-a"))

    private fun buildSut(
        storage: FakeSdkStorage = FakeSdkStorage(),
        scope: ResourceScope = resourceScope,
    ): Pair<AslStorageImpl, FakeSdkStorage> =
        AslStorageImpl(storage, scope, json) to storage

    @Test
    fun saveSession_storesSerializedSession_validSession() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val session = buildSession(requestCounter = 42L, encCounter = 13L)

        // Act
        sut.saveSession(session)

        // Assert
        val stored = storage.getAll()
        val sessionKey = stored.keys.first { it.startsWith(AslStorageImpl.SESSION_PREFIX) }
        val storedJson = stored[sessionKey] ?: error("Session not found in storage for key: $sessionKey")
        val decoded = json.decodeFromString<EstablishedSession>(storedJson)
        assertEquals(42L, decoded.requestCounter)
        assertEquals(13L, decoded.encCounter)
    }

    @Test
    fun saveSession_usesStorageKey_forSessionStorage() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        val session = buildSession()

        // Act
        sut.saveSession(session)

        // Assert
        val sessionKeys = storage.getAll().keys.filter { it.startsWith(AslStorageImpl.SESSION_PREFIX) }
        assertEquals(1, sessionKeys.size)
        assertFalse(sessionKeys.first().contains("example.com"))
    }

    @Test
    fun getCurrentSession_returnsSession_validStoredSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveSession(buildSession(requestCounter = 99L, encCounter = 77L))

        // Act
        val result = sut.getCurrentSession()

        // Assert
        assertEquals(99L, result!!.requestCounter)
        assertEquals(77L, result.encCounter)
    }

    @Test
    fun getCurrentSession_returnsNull_noStoredSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getCurrentSession()

        // Assert
        assertNull(result)
    }

    @Test
    fun getCurrentSession_throwsException_invalidJson() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        val (sut, _) = buildSut(storage)
        sut.saveSession(buildSession())
        val sessionKey = storage.getAll().keys.first { it.startsWith(AslStorageImpl.SESSION_PREFIX) }
        storage.put(sessionKey, "{invalid json}")

        // Act & Assert
        assertFailsWith<Exception> {
            sut.getCurrentSession()
        }
    }

    @Test
    fun clear_removesSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveSession(buildSession())

        // Act
        sut.clear()

        // Assert
        assertNull(sut.getCurrentSession())
    }

    @Test
    fun clear_removesAllSessions() = runTest {
        // Arrange
        val (sut, storage) = buildSut()
        sut.saveSession(buildSession(requestCounter = 1L))

        // Act
        sut.clear()

        // Assert
        val sessionKeys = storage.getAll().keys.filter { it.startsWith(AslStorageImpl.SESSION_PREFIX) }
        assertTrue(sessionKeys.isEmpty())
    }

    @Test
    fun clear_handlesEmpty_noSessions() = runTest {
        // Arrange
        val (sut, storage) = buildSut()

        // Act
        sut.clear()

        // Assert
        assertTrue(storage.getAll().isEmpty())
    }

    @Test
    fun clear_removesOnlyAslSessions_mixedStorage() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        storage.put("other_key_1", "value1")
        storage.put("other_key_2", "value2")
        val (sut, _) = buildSut(storage)
        sut.saveSession(buildSession())

        // Act
        sut.clear()

        // Assert
        val stored = storage.getAll()
        assertEquals(2, stored.size)
        assertTrue(stored.containsKey("other_key_1"))
        assertTrue(stored.containsKey("other_key_2"))
    }

    @Test
    fun saveCachedCertData_storesAndReturnsCertData() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val certData = buildCertData()

        // Act
        sut.saveCachedCertData("abc123", 1, certData)
        val result = sut.getCachedCertData("abc123", 1)

        // Assert
        assertEquals(certData.cert.toList(), result!!.cert.toList())
        assertEquals(certData.ca.toList(), result.ca.toList())
        assertEquals(certData.rcaChain.map { it.toList() }, result.rcaChain.map { it.toList() })
    }

    @Test
    fun getCachedCertData_returnsNull_whenCacheMiss() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getCachedCertData("missing", 1)

        // Assert
        assertNull(result)
    }

    @Test
    fun getCachedCertData_usesHashAndVersionAsCacheKey() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val certV1 = buildCertData(certFirstByte = 1)
        val certV2 = buildCertData(certFirstByte = 2)

        // Act
        sut.saveCachedCertData("same-hash", 1, certV1)
        sut.saveCachedCertData("same-hash", 2, certV2)

        // Assert
        assertEquals(1, sut.getCachedCertData("same-hash", 1)!!.cert.first())
        assertEquals(2, sut.getCachedCertData("same-hash", 2)!!.cert.first())
    }

    @Test
    fun getCachedCertData_usesHashAsCacheKey() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val certA = buildCertData(certFirstByte = 10)
        val certB = buildCertData(certFirstByte = 20)

        // Act
        sut.saveCachedCertData("hash-a", 1, certA)
        sut.saveCachedCertData("hash-b", 1, certB)

        // Assert
        assertEquals(10, sut.getCachedCertData("hash-a", 1)!!.cert.first())
        assertEquals(20, sut.getCachedCertData("hash-b", 1)!!.cert.first())
    }

    @Test
    fun saveCachedCertData_doesNotOverwriteSession() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val session = buildSession(requestCounter = 123L)
        sut.saveSession(session)

        // Act
        sut.saveCachedCertData("abc123", 1, buildCertData())

        // Assert
        assertEquals(123L, sut.getCurrentSession()!!.requestCounter)
    }

    @Test
    fun clear_removesCachedCertData() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveCachedCertData("abc123", 1, buildCertData())

        // Act
        sut.clear()

        // Assert
        assertNull(sut.getCachedCertData("abc123", 1))
    }

    @Test
    fun clear_removesSessionAndCachedCertData() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        sut.saveSession(buildSession())
        sut.saveCachedCertData("abc123", 1, buildCertData())

        // Act
        sut.clear()

        // Assert
        assertNull(sut.getCurrentSession())
        assertNull(sut.getCachedCertData("abc123", 1))
    }

    @Test
    fun clear_removesOnlyAslData_mixedStorageWithCertCache() = runTest {
        // Arrange
        val storage = FakeSdkStorage()
        storage.put("other_key_1", "value1")
        storage.put("other_key_2", "value2")

        val (sut, _) = buildSut(storage)
        sut.saveSession(buildSession())
        sut.saveCachedCertData("abc123", 1, buildCertData())

        // Act
        sut.clear()

        // Assert
        val stored = storage.getAll()
        assertEquals(2, stored.size)
        assertTrue(stored.containsKey("other_key_1"))
        assertTrue(stored.containsKey("other_key_2"))
    }

    private fun buildCertData(
        certFirstByte: Byte = 1,
    ): CertData = CertData(
        cert = byteArrayOf(certFirstByte, 2, 3),
        ca = byteArrayOf(4, 5, 6),
        rcaChain = listOf(
            byteArrayOf(7, 8, 9),
            byteArrayOf(10, 11, 12),
        ),
    )

    private class FakeSdkStorage : SdkStorage {
        private val store = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = store[key]
        override suspend fun put(key: String, value: String) { store[key] = value }
        override suspend fun remove(key: String) { store.remove(key) }
        override suspend fun clear() { store.clear() }
        fun getAll(): Map<String, String> = store.toMap()
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
