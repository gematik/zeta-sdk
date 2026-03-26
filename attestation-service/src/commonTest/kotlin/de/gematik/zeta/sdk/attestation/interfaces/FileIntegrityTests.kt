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

import ServiceConfig
import de.gematik.zeta.sdk.attestation.model.TpmQuoteResult
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityResponse
import de.gematik.zeta.sdk.attestation.tpm.TpmAccessOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileIntegrityTest {

    @Test
    fun initialize_scansFilesAndReturnsHashes() {
        // Arrange
        val sut = buildSut(files = listOf("file1.txt", "file2.txt"))

        // Act
        val result = sut.initialize()

        // Assert
        assertEquals(2, result.size)
        assertEquals("hash_file1.txt", result["file1.txt"])
        assertEquals("hash_file2.txt", result["file2.txt"])
    }

    @Test
    fun initialize_resetsPcrAndExtends_whenResetIntegrityTrue() {
        // Arrange
        val fakeTpm = FakeTpmAccess()
        val sut = buildSut(tpm = fakeTpm)

        // Act
        sut.initialize(resetIntegrity = true)

        // Assert
        assertTrue(fakeTpm.resetPCRCalled)
        assertTrue(fakeTpm.extendPCRCalled)
        assertEquals(23, fakeTpm.lastResetPcrIndex)
        assertEquals(23, fakeTpm.lastExtendPcrIndex)
    }

    @Test
    fun initialize_doesNotResetPcr_whenResetIntegrityFalse() {
        // Arrange
        val fakeTpm = FakeTpmAccess()
        val sut = buildSut(tpm = fakeTpm)

        // Act
        sut.initialize(resetIntegrity = false)

        // Assert
        assertFalse(fakeTpm.resetPCRCalled)
        assertFalse(fakeTpm.extendPCRCalled)
    }

    @Test
    fun isIntact_returnsTrue_whenPcrMatches() {
        // Arrange
        val fakeHashCalculator = FakeFileHashCalculator()
        val expectedPcr = byteArrayOf(0x01, 0x02, 0x03)
        fakeHashCalculator.expectedPcrResult = expectedPcr
        val fakeTpm = FakeTpmAccess(pcrValue = expectedPcr)
        val sut = buildSut(tpm = fakeTpm, hashCalculator = fakeHashCalculator)

        // Act
        val result = sut.isIntact()

        // Assert
        assertTrue(result)
    }

    @Test
    fun isIntact_returnsFalse_whenPcrDoesNotMatch() {
        // Arrange
        val fakeHashCalculator = FakeFileHashCalculator()
        fakeHashCalculator.expectedPcrResult = byteArrayOf(0x01, 0x02, 0x03)
        val fakeTpm = FakeTpmAccess(pcrValue = byteArrayOf(0x04, 0x05, 0x06))
        val sut = buildSut(tpm = fakeTpm, hashCalculator = fakeHashCalculator)

        // Act
        val result = sut.isIntact()

        // Assert
        assertFalse(result)
    }

    @Test
    fun isIntact_returnsFalse_whenScanThrowsException() {
        // Arrange
        val fakeScanner = FakeFileScanner(scanException = RuntimeException("Scan failed"))
        val sut = buildSut(fileScanner = fakeScanner)

        // Act
        val result = sut.isIntact()

        // Assert
        assertFalse(result)
    }

    @Test
    fun isIntact_returnsFalse_whenFileMissing() {
        // Arrange
        val fakeScanner = FakeFileScanner(scanResults = mapOf("file1.txt" to "hash1", "file2.txt" to null))
        val sut = buildSut(fileScanner = fakeScanner, files = listOf("file1.txt", "file2.txt"))

        // Act
        val result = sut.isIntact()

        // Assert
        assertFalse(result)
    }

    @Test
    fun verifyIntegrity_returnsValidResults_whenHashesMatch() {
        // Arrange
        val fakeHashCalculator = FakeFileHashCalculator(sha256Results = mapOf("file1.txt" to "hash_file1.txt"))
        val sut = buildSut(hashCalculator = fakeHashCalculator)
        sut.initialize()

        // Act
        val result = sut.verifyIntegrity(listOf("file1.txt"))

        // Assert
        assertTrue(result.success == true)
        assertTrue(result.results["file1.txt"]?.isValid == true)
    }

    @Test
    fun verifyIntegrity_returnsInvalidResults_whenHashesDiffer() {
        // Arrange
        val fakeHashCalculator = FakeFileHashCalculator(sha256Results = mapOf("file1.txt" to "different_hash"))
        val sut = buildSut(hashCalculator = fakeHashCalculator, files = listOf("file1.txt"))
        sut.initialize()

        // Act
        val result = sut.verifyIntegrity(listOf("file1.txt"))

        // Assert
        assertFalse(result.success == true)
        assertFalse(result.results["file1.txt"]?.isValid == true)
    }

    @Test
    fun verifyIntegrity_returnsInvalid_whenHashCalculationFails() {
        // Arrange
        val fakeHashCalculator = FakeFileHashCalculator(sha256Exception = RuntimeException("Hash failed"))
        val sut = buildSut(hashCalculator = fakeHashCalculator, files = listOf("file1.txt"))
        sut.initialize()

        // Act
        val result = sut.verifyIntegrity(listOf("file1.txt"))

        // Assert
        assertFalse(result.success == true)
        assertNull(result.results["file1.txt"]?.actualHash)
    }

    @Test
    fun verifyIntegrity_returnsInvalid_whenFileNotInExpected() {
        // Arrange
        val sut = buildSut(files = listOf("file1.txt"))
        sut.initialize()

        // Act
        val result = sut.verifyIntegrity(listOf("unknown.txt"))

        // Assert
        assertNull(result.results["unknown.txt"]?.expectedHash)
    }

    @Test
    fun currentIntegrityState_verifiesConfigFiles() {
        // Arrange
        val sut = buildSut(files = listOf("LICENSE", "NOTICE"))
        sut.initialize()

        // Act
        val result = sut.currentIntegrityState()

        // Assert
        assertEquals(2, result.results.size)
        assertTrue(result.results.containsKey("LICENSE"))
        assertTrue(result.results.containsKey("NOTICE"))
    }

    @Test
    fun subscribe_returnsUnsubscribeFunction() {
        // Arrange
        val sut = buildSut()

        // Act
        val unsubscribe = sut.subscribe { }

        // Assert
        // Should not throw
        unsubscribe()
    }

    @Test
    fun stopIntegrityMonitor_stopsScanner() {
        // Arrange
        val fakeScanner = FakeFileScanner()
        val sut = buildSut(fileScanner = fakeScanner)

        // Act
        sut.stopIntegrityMonitor()

        // Assert
        assertTrue(fakeScanner.monitoringStopped)
    }

    private class FakeTpmAccess(
        private val pcrValue: ByteArray = ByteArray(32),
    ) : TpmAccessOperations {
        var resetPCRCalled = false
        var extendPCRCalled = false
        var lastResetPcrIndex: Int? = null
        var lastExtendPcrIndex: Int? = null

        override fun isAvailable() = true
        override fun readPCRs(pcrSelection: List<Int>): Map<Int, ByteArray> {
            return pcrSelection.associateWith { pcrValue }
        }
        override fun extendPCR(pcrIndex: Int, data: ByteArray) {
            extendPCRCalled = true
            lastExtendPcrIndex = pcrIndex
        }
        override fun resetPCR(pcrIndex: Int) {
            resetPCRCalled = true
            lastResetPcrIndex = pcrIndex
        }
        override fun getEventLog() = byteArrayOf()
        override fun getEKCertificateChain() = emptyList<ByteArray>()
        override fun provisionAttestationKey() = byteArrayOf()
        override fun removeAttestationKey() {}
        override fun generateQuote(attChallengeBytes: ByteArray, pcrSelection: List<Int>) =
            TpmQuoteResult(byteArrayOf(), byteArrayOf(), byteArrayOf())
    }

    private class FakeFileScanner(
        private val scanResults: Map<String, String?>? = null,
        private val scanException: Exception? = null,
    ) : FileScannerOperations {
        var monitoringStarted = false
        var monitoringStopped = false

        override fun scanFiles(files: List<String>): Map<String, String?> {
            if (scanException != null) throw scanException
            return scanResults ?: files.associateWith { "hash_$it" }
        }
        override fun startMonitoring(files: List<String>, onModified: (String, String) -> Unit) {
            monitoringStarted = true
        }
        override fun stopMonitoring() { monitoringStopped = true }
    }

    private class FakeFileHashCalculator(
        private val sha256Results: Map<String, String>? = null,
        private val sha256Exception: Exception? = null,
        var expectedPcrResult: ByteArray = ByteArray(32),
    ) : FileHashCalculatorOperations {
        override fun calculateSHA256(filePath: String): String {
            if (sha256Exception != null) throw sha256Exception
            return sha256Results?.get(filePath) ?: "hash_$filePath"
        }
        override fun computeExpectedPcr(hash: ByteArray) = expectedPcrResult
        override fun computeMasterHash(fileHashes: Map<String, String?>) = byteArrayOf(0x01, 0x02)
    }

    private fun buildSut(
        tpm: TpmAccessOperations = FakeTpmAccess(),
        fileScanner: FileScannerOperations = FakeFileScanner(),
        hashCalculator: FileHashCalculatorOperations = FakeFileHashCalculator(),
        files: List<String> = listOf("file1.txt"),
    ): FileIntegrity {
        val config = ServiceConfig(
            files = files,
            port = 8081,
            pcrId = 23,
            enableFileIntegrity = true,
            enableQuote = true,
            enablePcrLog = true,
            enableEKCertificate = true,
            enableProcessOrigin = true,
            resetFileIntegrity = false,
        )
        return FileIntegrity(
            tpm = tpm,
            fileScanner = fileScanner,
            hashCalculator = hashCalculator,
            config = config,
        )
    }
}
