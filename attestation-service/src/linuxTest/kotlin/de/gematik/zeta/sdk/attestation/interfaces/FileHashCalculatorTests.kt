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

package de.gematik.zeta.sdk.attestation.interfaces

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class FileHashCalculatorTest {

    @Test
    fun calculateSha256_returnsValidHash_existingFile() {
        // Arrange
        val path = "/tmp/test_hash_file.txt"
        writeTestFile(path, "hello world")

        // Act
        val hash = FileHashCalculator.calculateSHA256(path)

        // Assert
        assertEquals(64, hash.length)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)

        // Cleanup
        deleteTestFile(path)
    }

    @Test
    fun calculateSha256_returnsDifferentHash_differentContent() {
        // Arrange
        val path1 = "/tmp/test_hash_file1.txt"
        val path2 = "/tmp/test_hash_file2.txt"
        writeTestFile(path1, "content A")
        writeTestFile(path2, "content B")

        // Act
        val hash1 = FileHashCalculator.calculateSHA256(path1)
        val hash2 = FileHashCalculator.calculateSHA256(path2)

        // Assert
        assertNotEquals(hash1, hash2)

        // Cleanup
        deleteTestFile(path1)
        deleteTestFile(path2)
    }

    @Test
    fun calculateSha256_returnsSameHash_sameContent() {
        // Arrange
        val path1 = "/tmp/test_hash_same1.txt"
        val path2 = "/tmp/test_hash_same2.txt"
        writeTestFile(path1, "identical content")
        writeTestFile(path2, "identical content")

        // Act
        val hash1 = FileHashCalculator.calculateSHA256(path1)
        val hash2 = FileHashCalculator.calculateSHA256(path2)

        // Assert
        assertEquals(hash1, hash2)

        // Cleanup
        deleteTestFile(path1)
        deleteTestFile(path2)
    }

    @Test
    fun calculateSha256_returnsHash_emptyFile() {
        // Arrange
        val path = "/tmp/test_hash_empty.txt"
        writeTestFile(path, "")

        // Act
        val hash = FileHashCalculator.calculateSHA256(path)

        // Assert
        assertEquals(64, hash.length)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)

        // Cleanup
        deleteTestFile(path)
    }

    @Test
    fun calculateSha256_throws_nonExistentFile() {
        // Arrange & Act & Assert
        assertFailsWith<Exception> {
            FileHashCalculator.calculateSHA256("/tmp/nonexistent_test_file_12345.txt")
        }
    }

    @Test
    fun computeMasterHash_returnsDeterministicHash() {
        // Arrange
        val hashes = mapOf(
            "file1.txt" to "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            "file2.txt" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )

        // Act
        val hash1 = FileHashCalculator.computeMasterHash(hashes)
        val hash2 = FileHashCalculator.computeMasterHash(hashes)

        // Assert
        assertEquals(32, hash1.size)
        assertTrue(hash1.contentEquals(hash2))
    }

    @Test
    fun computeMasterHash_returnsDifferentHash_differentInput() {
        // Arrange
        val hashes1 = mapOf(
            "file1.txt" to "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        )
        val hashes2 = mapOf(
            "file1.txt" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )

        // Act
        val hash1 = FileHashCalculator.computeMasterHash(hashes1)
        val hash2 = FileHashCalculator.computeMasterHash(hashes2)

        // Assert
        assertNotEquals(hash1.toList(), hash2.toList())
    }

    @Test
    fun computeMasterHash_isSortedByKey() {
        // Arrange
        val hash1Hex = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val hash2Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val hashesAB = mapOf("a.txt" to hash1Hex, "b.txt" to hash2Hex)
        val hashesBA = mapOf("b.txt" to hash2Hex, "a.txt" to hash1Hex)

        // Act
        val hash1 = FileHashCalculator.computeMasterHash(hashesAB)
        val hash2 = FileHashCalculator.computeMasterHash(hashesBA)

        // Assert
        assertTrue(hash1.contentEquals(hash2))
    }

    @Test
    fun computeMasterHash_handlesEmptyMap() {
        // Arrange
        val hashes = emptyMap<String, String?>()

        // Act
        val hash = FileHashCalculator.computeMasterHash(hashes)

        // Assert
        assertEquals(32, hash.size)
    }

    @Test
    fun computeExpectedPcr_returnsDeterministicResult() {
        // Arrange
        val hash = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        // Act
        val pcr1 = FileHashCalculator.computeExpectedPcr(hash)
        val pcr2 = FileHashCalculator.computeExpectedPcr(hash)

        // Assert
        assertEquals(32, pcr1.size)
        assertTrue(pcr1.contentEquals(pcr2))
    }

    @Test
    fun computeExpectedPcr_returnsDifferentResult_differentInput() {
        // Arrange
        val hash1 = byteArrayOf(0x01, 0x02, 0x03)
        val hash2 = byteArrayOf(0x04, 0x05, 0x06)

        // Act
        val pcr1 = FileHashCalculator.computeExpectedPcr(hash1)
        val pcr2 = FileHashCalculator.computeExpectedPcr(hash2)

        // Assert
        assertNotEquals(pcr1.toList(), pcr2.toList())
    }

    private fun writeTestFile(path: String, content: String) {
        val okioPath = path.toPath()
        val sink = FileSystem.SYSTEM.sink(okioPath).buffer()
        sink.writeUtf8(content)
        sink.close()
    }

    private fun deleteTestFile(path: String) {
        val okioPath = path.toPath()
        FileSystem.SYSTEM.delete(okioPath)
    }
}
