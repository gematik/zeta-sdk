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

package de.gematik.zeta.sdk.attestation.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {

    @Test
    fun get_returnsValueForExistingKey() {
        // Arrange
        CliArgs.init(arrayOf("--port=8080", "--host=localhost"))

        // Act & Assert
        assertEquals("8080", CliArgs.get("port"))
        assertEquals("localhost", CliArgs.get("host"))
    }

    @Test
    fun get_returnsNullForMissingKey() {
        // Arrange
        CliArgs.init(arrayOf("--port=8080"))

        // Act & Assert
        assertNull(CliArgs.get("missing"))
    }

    @Test
    fun get_returnsNullWhenNoArgs() {
        // Arrange
        CliArgs.init(emptyArray())

        // Act & Assert
        assertNull(CliArgs.get("any"))
    }

    @Test
    fun get_handlesEmptyValue() {
        // Arrange
        CliArgs.init(arrayOf("--key="))

        // Act & Assert
        assertEquals("", CliArgs.get("key"))
    }

    @Test
    fun get_handlesValueWithEquals() {
        // Arrange
        CliArgs.init(arrayOf("--url=http://host:8080/path?a=b"))

        // Act & Assert
        assertEquals("http://host:8080/path?a=b", CliArgs.get("url"))
    }

    @Test
    fun contains_returnsTrueForExistingKey() {
        // Arrange
        CliArgs.init(arrayOf("--verbose", "--port=8080"))

        // Act & Assert
        assertTrue(CliArgs.contains("verbose"))
        assertTrue(CliArgs.contains("port"))
    }

    @Test
    fun contains_returnsFalseForMissingKey() {
        // Arrange
        CliArgs.init(arrayOf("--verbose"))

        // Act & Assert
        assertFalse(CliArgs.contains("debug"))
    }

    @Test
    fun contains_returnsFalseWhenNoArgs() {
        // Arrange
        CliArgs.init(emptyArray())

        // Act & Assert
        assertFalse(CliArgs.contains("any"))
    }

    @Test
    fun init_replacesExistingArgs() {
        // Arrange
        CliArgs.init(arrayOf("--key=old"))
        assertEquals("old", CliArgs.get("key"))

        // Act
        CliArgs.init(arrayOf("--key=new"))

        // Assert
        assertEquals("new", CliArgs.get("key"))
    }

    @Test
    fun get_returnsFirstMatchWhenDuplicateKeys() {
        // Arrange
        CliArgs.init(arrayOf("--key=first", "--key=second"))

        // Act & Assert
        assertEquals("first", CliArgs.get("key"))
    }
}
