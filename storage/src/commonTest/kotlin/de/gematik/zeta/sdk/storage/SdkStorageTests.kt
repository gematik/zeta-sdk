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

import de.gematik.zeta.sdk.storage.ExtendedStorage.Companion.PRESENT_MARKER
import de.gematik.zeta.sdk.storage.ExtendedStorage.Companion.hash
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
        fakeStorage.store[hash("key")] = """{"a":"1","b":"2"}"""
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
        fakeStorage.store[hash("key")] = """{"a":"1"}"""
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
        assertNotNull(fakeStorage.store[hash("key")])
        assertTrue(fakeStorage.store[hash("key")]!!.contains("\"x\""))
        assertTrue(fakeStorage.store[hash("key")]!!.contains("\"y\""))
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
        fakeStorage.store[hash("key")] = """{"a":"1"}"""
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
        assertEquals("value", fakeStorage.store[hash("key")])
    }

    @Test
    fun get_delegatesToStorage() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store[hash("key")] = "value"
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
        fakeStorage.store[hash("key")] = "value"
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.remove("key")

        // Assert
        assertNull(fakeStorage.store[hash("key")])
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
        val result = sut.getMap("index_key")

        // Assert
        assertNull(result)
    }

    @Test
    fun getHashes_returnsList_singleEntry() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store[hash("index_key")] = """{"abc12345":"present"}"""
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getMap("index_key")

        // Assert
        assertNotNull(result)
        assertTrue(result.containsKey("abc12345"))
    }

    @Test
    fun getHashes_returnsList_multipleEntries() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store[hash("index_key")] = """{"abc12345":"present","def67890":"present","ghi11111":"present"}"""
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getMap("index_key")

        // Assert
        assertNotNull(result)
        assertEquals(3, result.size)
        assertTrue(result.containsKey("abc12345"))
        assertTrue(result.containsKey("def67890"))
        assertTrue(result.containsKey("ghi11111"))
    }

    @Test
    fun getHashes_filtersBlankEntries() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        fakeStorage.store[hash("index_key")] = """{"abc12345":"present","def67890":"present"}"""
        val (sut, _) = buildSut(fakeStorage)

        // Act
        val result = sut.getMap("index_key")

        // Assert
        assertNotNull(result)
        assertEquals(2, result.size)
    }

    @Test
    fun registerHash_storesHash_newEntry() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.upsertStringMap("index_key") { it["abc12345"] = PRESENT_MARKER }

        // Assert
        val result = sut.getMap("index_key")
        assertNotNull(result)
        assertTrue(result.containsKey("abc12345"))
    }

    @Test
    fun registerHash_returnsHash_consistentWithHashFunction() = runTest {
        // Arrange
        val (sut, _) = buildSut()
        val key = "https://example.com"

        // Act
        val hash = hash(key)
        sut.upsertStringMap("index_key") { it[hash] = PRESENT_MARKER }
        val result = sut.getMap("index_key")

        // Assert
        assertNotNull(result)
        assertTrue(result.containsKey(hash))
    }

    @Test
    fun registerHash_doesNotDuplicate_sameHashRegisteredTwice() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)
        val key = "https://example.com"

        // Act
        sut.upsertStringMap("index_key") { it[key] = PRESENT_MARKER }
        sut.upsertStringMap("index_key") { it[key] = PRESENT_MARKER }

        // Assert
        val result = sut.getMap("index_key")
        assertNotNull(result)
        assertEquals(1, result.size)
    }

    @Test
    fun registerHash_appendsHash_existingEntries() = runTest {
        // Arrange
        val fakeStorage = FakeSdkStorage()
        val (sut, _) = buildSut(fakeStorage)

        // Act
        sut.upsertStringMap("index_key") { it["https://first.com"] = PRESENT_MARKER }
        sut.upsertStringMap("index_key") { it["https://second.com"] = PRESENT_MARKER }

        // Assert
        val result = sut.getMap("index_key")
        assertNotNull(result)
        assertEquals(2, result.size)
    }

    @Test
    fun putIndexed_isolatesEntries_perResourceScope() = runTest {
        // Arrange
        val shared = FakeSdkStorage()
        val a = ExtendedStorage(shared, ResourceScope("a.example.com", listOf("scope")))
        val b = ExtendedStorage(shared, ResourceScope("b.example.com", listOf("scope")))

        // Act
        a.putIndexed("tpm_key_index", "tpm", mapOf("client_private_key" to "KEY_A"))
        b.putIndexed("tpm_key_index", "tpm", mapOf("client_private_key" to "KEY_B"))

        // Assert
        assertEquals("KEY_A", a.getIndexed("tpm", "client_private_key"))
        assertEquals("KEY_B", b.getIndexed("tpm", "client_private_key"))
    }

    @Test
    fun clearIndexed_keepsEntries_ofOtherResourceScope() = runTest {
        // Arrange
        val shared = FakeSdkStorage()
        val a = ExtendedStorage(shared, ResourceScope("a.example.com", listOf("scope")))
        val b = ExtendedStorage(shared, ResourceScope("b.example.com", listOf("scope")))
        a.putIndexed("tpm_key_index", "tpm", mapOf("client_private_key" to "KEY_A"))
        b.putIndexed("tpm_key_index", "tpm", mapOf("client_private_key" to "KEY_B"))

        // Act
        b.clearIndexed("tpm_key_index", "tpm", listOf("client_private_key"))

        // Assert
        assertEquals("KEY_A", a.getIndexed("tpm", "client_private_key"))
        assertNull(b.getIndexed("tpm", "client_private_key"))
    }

    @Test
    fun removeIndexed_keepsEntries_ofOtherResourceScope() = runTest {
        // Arrange
        val shared = FakeSdkStorage()
        val a = ExtendedStorage(shared, ResourceScope("a.example.com", listOf("scope")))
        val b = ExtendedStorage(shared, ResourceScope("b.example.com", listOf("scope")))
        a.putIndexed("tpm_key_index", "tpm", mapOf("dpop_private_key" to "KEY_A"))
        b.putIndexed("tpm_key_index", "tpm", mapOf("dpop_private_key" to "KEY_B"))

        // Act
        b.removeIndexed("tpm_key_index", "tpm", listOf("dpop_private_key"))

        // Assert
        assertEquals("KEY_A", a.getIndexed("tpm", "dpop_private_key"))
        assertNull(b.getIndexed("tpm", "dpop_private_key"))
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
