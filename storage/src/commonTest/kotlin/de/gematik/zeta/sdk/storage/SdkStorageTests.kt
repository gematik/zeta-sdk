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

package de.gematik.zeta.sdk.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdkStorageTest {

    @Test
    fun getMap_returnsNull_keyMissing() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getMap("nonexistent_key")

        // Assert
        assertNull(result)
    }

    @Test
    fun getMap_returnsNull_valueBlank() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = "   "
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getMap("key")

        // Assert
        assertNull(result)
    }

    @Test
    fun getMap_returnsNull_corruptJson() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = "not-valid-json{{{"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getMap("key")

        // Assert
        assertNull(result)
    }

    @Test
    fun getMap_returnsMap_validJson() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = """{"a":"1","b":"2"}"""
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getMap("key")

        // Assert
        assertNotNull(result)
        assertEquals("1", result["a"])
        assertEquals("2", result["b"])
    }

    @Test
    fun getMap_returnsMutableMap_canBeModified() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = """{"a":"1"}"""
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getMap("key")
        result?.put("b", "2")

        // Assert
        assertNotNull(result)
        assertEquals("2", result["b"])
    }

    @Test
    fun putMap_storesEncodedJson() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)
        val map = mutableMapOf("x" to "1", "y" to "2")

        // Act
        sut.putMap("key", map)

        // Assert
        assertNotNull(fakeStorage.store["key"])
        assertTrue(fakeStorage.store["key"]!!.contains("\"x\""))
        assertTrue(fakeStorage.store["key"]!!.contains("\"y\""))
    }

    @Test
    fun upsertStringMap_createsNewMap_keyMissing() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.upsertStringMap("key") { it["new"] = "value" }

        // Assert
        val result = sut.getMap("key")
        assertNotNull(result)
        assertEquals("value", result["new"])
    }

    @Test
    fun upsertStringMap_updatesExistingMap_keyPresent() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = """{"existing":"old"}"""
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.upsertStringMap("key") { it["existing"] = "new" }

        // Assert
        val result = sut.getMap("key")
        assertNotNull(result)
        assertEquals("new", result["existing"])
    }

    @Test
    fun upsertStringMap_addsEntry_existingMapPreserved() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = """{"a":"1"}"""
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.upsertStringMap("key") { it["b"] = "2" }

        // Assert
        val result = sut.getMap("key")
        assertNotNull(result)
        assertEquals("1", result["a"])
        assertEquals("2", result["b"])
    }

    @Test
    fun put_delegatesToStorage() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.put("key", "value")

        // Assert
        assertEquals("value", fakeStorage.store["key"])
    }

    @Test
    fun get_delegatesToStorage() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = "value"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.get("key")

        // Assert
        assertEquals("value", result)
    }

    @Test
    fun get_returnsNull_keyMissing() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.get("nonexistent")

        // Assert
        assertNull(result)
    }

    @Test
    fun remove_delegatesToStorage() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key"] = "value"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.remove("key")

        // Assert
        assertNull(fakeStorage.store["key"])
    }

    @Test
    fun clear_delegatesToStorage() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["key1"] = "value1"
        fakeStorage.store["key2"] = "value2"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.clear()

        // Assert
        assertTrue(fakeStorage.store.isEmpty())
    }

    @Test
    fun hash_returnsString_maxEightChars() {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.hash("https://example.com")

        // Assert
        assertNotNull(result)
        assertTrue(result.length <= 8)
    }

    @Test
    fun hash_returnsSameHash_sameInput() {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result1 = sut.hash("https://example.com")
        val result2 = sut.hash("https://example.com")

        // Assert
        assertEquals(result1, result2)
    }

    @Test
    fun hash_returnsDifferentHash_differentInput() {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result1 = sut.hash("https://example.com")
        val result2 = sut.hash("https://other.com")

        // Assert
        assertTrue(result1 != result2)
    }

    @Test
    fun getHashes_returnsEmptyList_keyMissing() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.getHashes("index_key")

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun getHashes_returnsList_singleEntry() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["index_key"] = "abc12345"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getHashes("index_key")

        // Assert
        assertEquals(listOf("abc12345"), result)
    }

    @Test
    fun getHashes_returnsList_multipleEntries() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["index_key"] = "abc12345;def67890;ghi11111"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getHashes("index_key")

        // Assert
        assertEquals(listOf("abc12345", "def67890", "ghi11111"), result)
    }

    @Test
    fun getHashes_filtersBlankEntries() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store["index_key"] = "abc12345;;def67890;"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getHashes("index_key")

        // Assert
        assertEquals(listOf("abc12345", "def67890"), result)
    }

    @Test
    fun registerHash_storesHash_newEntry() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val hash = sut.registerHash("index_key", "https://example.com")

        // Assert
        val stored = fakeStorage.store["index_key"]
        assertNotNull(stored)
        assertTrue(stored.contains(hash))
    }

    @Test
    fun registerHash_returnsHash_consistentWithHashFunction() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val registeredHash = sut.registerHash("index_key", "https://example.com")
        val directHash = sut.hash("https://example.com")

        // Assert
        assertEquals(directHash, registeredHash)
    }

    @Test
    fun registerHash_doesNotDuplicate_sameHashRegisteredTwice() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.registerHash("index_key", "https://example.com")
        sut.registerHash("index_key", "https://example.com")

        // Assert
        val hashes = sut.getHashes("index_key")
        assertEquals(1, hashes.size)
    }

    @Test
    fun registerHash_appendsHash_existingEntries() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.registerHash("index_key", "https://first.com")
        sut.registerHash("index_key", "https://second.com")

        // Assert
        val hashes = sut.getHashes("index_key")
        assertEquals(2, hashes.size)
    }

    private fun buildSut(fakeStorage: FakeSdkStorage = FakeSdkStorage()) =
        ExtendedStorage(fakeStorage) to fakeStorage

    private class FakeSdkStorage : SdkStorage {
        val store = mutableMapOf<String, String>()

        override suspend fun put(key: String, value: String) { store[key] = value }
        override suspend fun get(key: String): String? = store[key]
        override suspend fun remove(key: String) { store.remove(key) }
        override suspend fun clear() { store.clear() }
    }
}
