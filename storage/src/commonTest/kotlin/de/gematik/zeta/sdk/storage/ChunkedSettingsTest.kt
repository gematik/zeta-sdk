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

import com.russhwolf.settings.MapSettings
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkedSettingsTest {

    @Test
    fun init_throws_whenChunkSizeIsZero() {
        assertFailsWith<IllegalArgumentException> {
            ChunkedSettings(delegate = MapSettings(), chunkSize = 0)
        }
    }

    @Test
    fun init_throws_whenChunkSizeIsNegative() {
        assertFailsWith<IllegalArgumentException> {
            ChunkedSettings(delegate = MapSettings(), chunkSize = -1)
        }
    }

    @Test
    fun putString_storesValueAsSingleEntry_whenSmallerThanChunkSize() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        sut.putString("test", "12345")

        // Assert
        assertEquals("12345", sut.getStringOrNull("test"))
        assertEquals("12345", delegate.getStringOrNull("test"))
        assertNull(delegate.getStringOrNull("test:chunks"))
    }

    @Test
    fun putString_storesValueAsSingleEntry_whenSizeEqualsChunkSize() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        sut.putString("test", "1234567890")

        // Assert
        assertEquals("1234567890", sut.getStringOrNull("test"))
        assertEquals("1234567890", delegate.getStringOrNull("test"))
        assertNull(delegate.getStringOrNull("test:chunks"))
    }

    @Test
    fun putString_storesValueAsChunks_whenLargerThanChunkSize() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        sut.putString("test", "12345678901")

        // Assert
        assertNull(delegate.getStringOrNull("test"))
        assertEquals("1234567890", delegate.getStringOrNull("test:c0"))
        assertEquals("1", delegate.getStringOrNull("test:c1"))
        assertEquals("2", delegate.getStringOrNull("test:chunks"))
    }

    @Test
    fun getStringOrNull_reconstructsChunkedValue() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)
        sut.putString("test", "abcdefghijkl")

        // Act
        val result = sut.getStringOrNull("test")

        // Assert
        assertEquals("abcdefghijkl", result)
    }

    @Test
    fun getStringOrNull_returnsNull_whenKeyDoesNotExist() {
        // Arrange
        val sut = ChunkedSettings(delegate = MapSettings(), chunkSize = 10)

        // Act
        val result = sut.getStringOrNull("missing")

        // Assert
        assertNull(result)
    }

    @Test
    fun getString_returnsDefaultValue_whenKeyDoesNotExist() {
        // Arrange
        val sut = ChunkedSettings(delegate = MapSettings(), chunkSize = 10)

        // Act
        val result = sut.getString("missing", "fallback")

        // Assert
        assertEquals("fallback", result)
    }

    @Test
    fun getStringOrNull_returnsNull_whenChunkCountIsInvalid() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test:chunks", "invalid")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        val result = sut.getStringOrNull("test")

        // Assert
        assertNull(result)
    }

    @Test
    fun getStringOrNull_returnsNull_whenChunkCountIsZero() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test:chunks", "0")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        val result = sut.getStringOrNull("test")

        // Assert
        assertNull(result)
    }

    @Test
    fun getStringOrNull_returnsNull_whenChunkCountIsNegative() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test:chunks", "-1")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        val result = sut.getStringOrNull("test")

        // Assert
        assertNull(result)
    }

    @Test
    fun getStringOrNull_returnsNull_whenChunkIsMissing() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test:c0", "abc")
        delegate.putString("test:chunks", "2")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        val result = sut.getStringOrNull("test")

        // Assert
        assertNull(result)
    }

    @Test
    fun putString_overwritesSingleEntryWithChunkedEntry() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)

        sut.putString("test", "abc")
        assertEquals("abc", delegate.getStringOrNull("test"))

        // Act
        sut.putString("test", "abcdefghijk")

        // Assert
        assertNull(delegate.getStringOrNull("test"))
        assertEquals("abcde", delegate.getStringOrNull("test:c0"))
        assertEquals("fghij", delegate.getStringOrNull("test:c1"))
        assertEquals("k", delegate.getStringOrNull("test:c2"))
        assertEquals("3", delegate.getStringOrNull("test:chunks"))
        assertEquals("abcdefghijk", sut.getStringOrNull("test"))
    }

    @Test
    fun putString_overwritesChunkedEntryWithSingleEntry() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)
        sut.putString("test", "abcdefghijk")
        assertEquals("3", delegate.getStringOrNull("test:chunks"))

        // Act
        sut.putString("test", "abc")

        // Assert
        assertEquals("abc", delegate.getStringOrNull("test"))
        assertNull(delegate.getStringOrNull("test:chunks"))
        assertNull(delegate.getStringOrNull("test:c0"))
        assertNull(delegate.getStringOrNull("test:c1"))
        assertNull(delegate.getStringOrNull("test:c2"))
        assertEquals("abc", sut.getStringOrNull("test"))
    }

    @Test
    fun putString_removesObsoleteChunks_whenNewChunkedValueUsesFewerChunks() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)

        sut.putString("test", "abcdefghijklmnop")
        assertEquals("4", delegate.getStringOrNull("test:chunks"))

        // Act
        sut.putString("test", "abcdefgh")

        // Assert
        assertEquals("2", delegate.getStringOrNull("test:chunks"))
        assertEquals("abcde", delegate.getStringOrNull("test:c0"))
        assertEquals("fgh", delegate.getStringOrNull("test:c1"))
        assertNull(delegate.getStringOrNull("test:c2"))
        assertNull(delegate.getStringOrNull("test:c3"))
        assertEquals("abcdefgh", sut.getStringOrNull("test"))
    }

    @Test
    fun remove_removesSingleEntry() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)
        sut.putString("test", "abc")

        // Act
        sut.remove("test")

        // Assert
        assertNull(sut.getStringOrNull("test"))
        assertNull(delegate.getStringOrNull("test"))
    }

    @Test
    fun remove_removesAllChunksAndManifest() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)
        sut.putString("test", "abcdefghijkl")
        assertEquals("3", delegate.getStringOrNull("test:chunks"))

        // Act
        sut.remove("test")

        // Assert
        assertNull(delegate.getStringOrNull("test"))
        assertNull(delegate.getStringOrNull("test:chunks"))
        assertNull(delegate.getStringOrNull("test:c0"))
        assertNull(delegate.getStringOrNull("test:c1"))
        assertNull(delegate.getStringOrNull("test:c2"))
        assertNull(sut.getStringOrNull("test"))
    }

    @Test
    fun remove_removesManifest_whenChunkCountIsInvalid() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test:chunks", "invalid")
        delegate.putString("test", "stale-value")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)

        // Act
        sut.remove("test")

        // Assert
        assertNull(delegate.getStringOrNull("test:chunks"))
        assertNull(delegate.getStringOrNull("test"))
    }

    @Test
    fun putString_chunksEncryptedValue_evenWhenPlaintextWasUnderChunkSize() {
        // Arrange
        val delegate = MapSettings()
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)
        val secureSettings = EncryptedSettings(
            delegate = sut,
            cipher = AesGcmCipherImpl(),
            cipherB64Key = testAesB64Key(),
        )
        val plaintext = "short"

        // Act
        secureSettings.putString("test", plaintext)

        // Assert
        assertNull(delegate.getStringOrNull("test"))
        assertEquals(
            expected = secureSettings.getStringOrNull("test"),
            actual = plaintext,
        )
    }

    @Test
    fun getStringOrNull_readsLegacyValue_storedBeforeChunkingExisted() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test", "legacy-value-written-without-chunking")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        val result = sut.getStringOrNull("test")

        // Assert
        assertEquals("legacy-value-written-without-chunking", result)
    }

    @Test
    fun getString_readsLegacyValue_withDefaultValueFallbackUnaffected() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("legacy-key", "legacy-value")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        val result = sut.getString("legacy-key", "fallback")

        // Assert
        assertEquals("legacy-value", result)
    }

    @Test
    fun hasKey_recognizesLegacyValue_withoutManifest() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("legacy-key", "legacy-value")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act & Assert
        assertTrue(sut.hasKey("legacy-key"))
        assertFalse(sut.hasKey("missing-key"))
    }

    @Test
    fun putString_overwritingLegacyValue_withSmallValue_staysSingleEntry() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test", "old-legacy-value")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        sut.putString("test", "new-value")

        // Assert
        assertEquals("new-value", delegate.getStringOrNull("test"))
        assertNull(delegate.getStringOrNull("test:chunks"))
        assertEquals("new-value", sut.getStringOrNull("test"))
    }

    @Test
    fun putString_overwritingLegacyValue_withLargeValue_migratesToChunked() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test", "old-legacy-value")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)

        // Act
        sut.putString("test", "abcdefghijkl")

        // Assert
        assertNull(delegate.getStringOrNull("test"))
        assertEquals("3", delegate.getStringOrNull("test:chunks"))
        assertEquals("abcdefghijkl", sut.getStringOrNull("test"))
    }

    @Test
    fun remove_removesLegacyValue_withoutManifest_cleanly() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("test", "legacy-value")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act
        sut.remove("test")

        // Assert
        assertNull(delegate.getStringOrNull("test"))
        assertNull(delegate.getStringOrNull("test:chunks"))
        assertNull(sut.getStringOrNull("test"))
    }

    @Test
    fun keys_includesLegacyEntries_viaDelegation() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("legacy-a", "value-a")
        delegate.putString("legacy-b", "value-b")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 10)

        // Act & Assert
        assertTrue("legacy-a" in sut.keys)
        assertTrue("legacy-b" in sut.keys)
    }

    @Test
    fun mixedLegacyAndChunkedEntries_coexistCorrectly() {
        // Arrange
        val delegate = MapSettings()
        delegate.putString("legacy-key", "plain-legacy-value")
        val sut = ChunkedSettings(delegate = delegate, chunkSize = 5)
        sut.putString("new-key", "abcdefghijkl")

        // Act
        val legacyResult = sut.getStringOrNull("legacy-key")
        val chunkedResult = sut.getStringOrNull("new-key")

        // Assert
        assertEquals("plain-legacy-value", legacyResult)
        assertEquals("abcdefghijkl", chunkedResult)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun testAesB64Key(): String =
        Base64.encode(Random.nextBytes(32))
}
