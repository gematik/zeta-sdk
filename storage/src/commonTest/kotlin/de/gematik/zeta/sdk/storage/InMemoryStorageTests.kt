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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryStorageTest {

    @Test
    fun put_storesValue_newKey() = runTest {
        // Arrange
        val sut = InMemoryStorage()

        // Act
        sut.put("key", "value")

        // Assert
        assertEquals("value", sut.map["key"])
    }

    @Test
    fun put_overwritesValue_existingKey() = runTest {
        // Arrange
        val sut = InMemoryStorage()
        sut.put("key", "old_value")

        // Act
        sut.put("key", "new_value")

        // Assert
        assertEquals("new_value", sut.map["key"])
    }

    @Test
    fun get_returnsValue_existingKey() = runTest {
        // Arrange
        val sut = InMemoryStorage()
        sut.put("key", "value")

        // Act
        val result = sut.get("key")

        // Assert
        assertEquals("value", result)
    }

    @Test
    fun get_returnsNull_missingKey() = runTest {
        // Arrange
        val sut = InMemoryStorage()

        // Act
        val result = sut.get("nonexistent")

        // Assert
        assertNull(result)
    }

    @Test
    fun remove_removesEntry_existingKey() = runTest {
        // Arrange
        val sut = InMemoryStorage()
        sut.put("key", "value")

        // Act
        sut.remove("key")

        // Assert
        assertNull(sut.map["key"])
    }

    @Test
    fun remove_doesNothing_missingKey() = runTest {
        // Arrange
        val sut = InMemoryStorage()
        sut.put("other_key", "value")

        // Act
        sut.remove("nonexistent")

        // Assert
        assertEquals("value", sut.map["other_key"])
    }

    @Test
    fun clear_removesAllEntries() = runTest {
        // Arrange
        val sut = InMemoryStorage()
        sut.put("key1", "value1")
        sut.put("key2", "value2")
        sut.put("key3", "value3")

        // Act
        sut.clear()

        // Assert
        assertTrue(sut.map.isEmpty())
    }

    @Test
    fun clear_doesNothing_emptyStorage() = runTest {
        // Arrange
        val sut = InMemoryStorage()

        // Act
        sut.clear()

        // Assert
        assertTrue(sut.map.isEmpty())
    }
}
