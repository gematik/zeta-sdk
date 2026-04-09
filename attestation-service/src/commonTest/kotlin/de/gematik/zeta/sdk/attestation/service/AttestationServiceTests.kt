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

package de.gematik.zeta.sdk.attestation.service

import AttestationService
import ServiceConfig
import de.gematik.zeta.sdk.attestation.interfaces.FileIntegrityOperations
import de.gematik.zeta.sdk.attestation.interfaces.FileScannerOperations
import de.gematik.zeta.sdk.attestation.interfaces.ProcessMonitorOperations
import de.gematik.zeta.sdk.attestation.model.AttestationRequest
import de.gematik.zeta.sdk.attestation.model.FileIntegrityResult
import de.gematik.zeta.sdk.attestation.model.FileMonitorRequest
import de.gematik.zeta.sdk.attestation.model.TpmQuoteResult
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityRequest
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityResponse
import de.gematik.zeta.sdk.attestation.tpm.TpmAccessOperations
import io.ktor.http.RequestConnectionPoint
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttestationServiceTest {
    @Test
    fun initialize_provisionsAkAndInitializesIntegrity() {
        // Arrange
        val fakeTpm = FakeTpmAccess()
        val fakeFileIntegrity = FakeFileIntegrity()
        val sut = buildSut(tpm = fakeTpm, fileIntegrity = fakeFileIntegrity, resetFileIntegrity = true)

        // Act
        sut.initialize()

        // Assert
        assertTrue(fakeTpm.provisionAttestationKeyCalled)
        assertTrue(fakeFileIntegrity.initializeCalled)
        assertTrue(fakeFileIntegrity.lastResetIntegrity)
    }

    @Test
    fun initialize_doesNotResetIntegrity_whenNotConfigured() {
        // Arrange
        val fakeFileIntegrity = FakeFileIntegrity()
        val sut = buildSut(fileIntegrity = fakeFileIntegrity, resetFileIntegrity = false)

        // Act
        sut.initialize()

        // Assert
        assertTrue(fakeFileIntegrity.initializeCalled)
        assertTrue(!fakeFileIntegrity.lastResetIntegrity)
    }

    @Test
    fun buildAttestationResponse_returnsError_emptyChallenge() {
        // Arrange
        val sut = buildSut()
        val request = AttestationRequest(attestationChallenge = "", pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.INVALID_ARGUMENT, response.error.code)
        assertEquals(response.error.message.contains("empty"), true)
    }

    @Test
    fun buildAttestationResponse_returnsError_emptyPcrSelection() {
        // Arrange
        val sut = buildSut()
        val challenge = Base64.encode("test-challenge".encodeToByteArray())
        val request = AttestationRequest(attestationChallenge = challenge, pcrSelection = emptyList())

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.INVALID_ARGUMENT, response.error.code)
    }

    @Test
    fun buildAttestationResponse_returnsError_invalidBase64Challenge() {
        // Arrange
        val sut = buildSut()
        val request = AttestationRequest(attestationChallenge = "not-valid-base64!!!", pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.INVALID_ARGUMENT, response.error.code)
        assertEquals(response.error.message.contains("base64"), true)
    }

    @Test
    fun buildAttestationResponse_returnsError_tpmNotAvailable() {
        // Arrange
        val fakeTpm = FakeTpmAccess(available = false)
        val sut = buildSut(tpm = fakeTpm)
        val challenge = Base64.encode("test-challenge".encodeToByteArray())
        val request = AttestationRequest(attestationChallenge = challenge, pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.TPM_NOT_AVAILABLE, response.error.code)
    }

    @Test
    fun buildAttestationResponse_returnsError_integrityViolated() {
        // Arrange
        val fakeFileIntegrity = FakeFileIntegrity(intact = false)
        val sut = buildSut(fileIntegrity = fakeFileIntegrity)
        val challenge = Base64.encode("test-challenge".encodeToByteArray())
        val request = AttestationRequest(attestationChallenge = challenge, pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.INTERNAL_ERROR, response.error.code)
    }

    @Test
    fun buildAttestationResponse_returnsError_processNotAllowed() {
        // Arrange
        val fakeMonitor = FakeProcessMonitor(processAllowed = false)
        val sut = buildSut(monitor = fakeMonitor)
        val challenge = Base64.encode("test-challenge".encodeToByteArray())
        val request = AttestationRequest(attestationChallenge = challenge, pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.PROCESS_NOT_ALLOWED, response.error.code)
    }

    @Test
    fun buildAttestationResponse_returnsError_tpmQuoteThrowsTpmException() {
        // Arrange
        val fakeTpm = FakeTpmAccess(quoteException = TpmException("TPM failure", 0x1A4u))
        val sut = buildSut(tpm = fakeTpm)
        val challenge = Base64.encode("test-challenge".encodeToByteArray())
        val request = AttestationRequest(attestationChallenge = challenge, pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.TPM_QUOTE_ERROR, response.error.code)
    }

    @Test
    fun buildAttestationResponse_returnsError_tpmQuoteThrowsGenericException() {
        // Arrange
        val fakeTpm = FakeTpmAccess(quoteException = RuntimeException("Unexpected"))
        val sut = buildSut(tpm = fakeTpm)
        val challenge = Base64.encode("test-challenge".encodeToByteArray())
        val request = AttestationRequest(attestationChallenge = challenge, pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNotNull(response.error)
        assertEquals(ErrorCode.INTERNAL_ERROR, response.error.code)
    }

    @Test
    fun buildAttestationResponse_returnsSuccess_allChecksPass() {
        // Arrange
        val sut = buildSut()
        val challenge = Base64.encode("test-challenge".encodeToByteArray())
        val request = AttestationRequest(attestationChallenge = challenge, pcrSelection = listOf(23))

        // Act
        val response = sut.buildAttestationResponse(request, FakeRequestConnectionPoint())

        // Assert
        assertNull(response.error)
        assertTrue(response.tpmAttestationKey!!.isNotEmpty())
        assertTrue(response.tpmQuote!!.isNotEmpty())
        assertTrue(response.tpmQuoteSignature!!.isNotEmpty())
        assertTrue(response.tpmEventLog!!.isNotEmpty())
    }

    @Test
    fun verifyIntegrity_delegatesToFileIntegrity() {
        // Arrange
        val fakeFileIntegrity = FakeFileIntegrity()
        val sut = buildSut(fileIntegrity = fakeFileIntegrity)
        val request = VerifyIntegrityRequest(filePaths = listOf("file1.txt", "file2.txt"))

        // Act
        val result = sut.verifyIntegrity(request)

        // Assert
        assertEquals(listOf("file1.txt", "file2.txt"), fakeFileIntegrity.lastVerifyFilePaths)
        assertNotNull(result)
    }

    @Test
    fun currentIntegrityState_usesConfigFiles() {
        // Arrange
        val fakeFileIntegrity = FakeFileIntegrity()
        val sut = buildSut(fileIntegrity = fakeFileIntegrity, files = listOf("LICENSE", "NOTICE"))

        // Act
        sut.currentIntegrityState()

        // Assert
        assertEquals(listOf("LICENSE", "NOTICE"), fakeFileIntegrity.lastVerifyFilePaths)
    }

    @Test
    fun health_returnsHealthCheck_tpmAvailable() {
        // Arrange
        val fakeTpm = FakeTpmAccess(available = true)
        val fakeMonitor = FakeProcessMonitor(running = true)
        val sut = buildSut(tpm = fakeTpm, monitor = fakeMonitor)

        // Act
        val health = sut.health()

        // Assert
        assertEquals("OK", health.status)
        assertTrue(health.tpmAvaliable)
        assertTrue(health.processRunning)
        assertTrue(health.uptime >= 0)
    }

    @Test
    fun health_returnsHealthCheck_tpmNotAvailable() {
        // Arrange
        val fakeTpm = FakeTpmAccess(available = false)
        val fakeMonitor = FakeProcessMonitor(running = false)
        val sut = buildSut(tpm = fakeTpm, monitor = fakeMonitor)

        // Act
        val health = sut.health()

        // Assert
        assertEquals("OK", health.status)
        assertTrue(!health.tpmAvaliable)
        assertTrue(!health.processRunning)
    }

    @Test
    fun processPid_returnsPidAndProcessName() {
        // Arrange
        val fakeMonitor = FakeProcessMonitor(socketPid = 1234, processName = "my-app")
        val sut = buildSut(monitor = fakeMonitor)

        // Act
        val result = sut.processPid(FakeRequestConnectionPoint())

        // Assert
        assertEquals(1234L, result.processPid)
        assertEquals("my-app", result.processName)
    }

    @Test
    fun processPid_returnsNulls_whenNotFound() {
        // Arrange
        val fakeMonitor = FakeProcessMonitor(socketPid = null, processName = null)
        val sut = buildSut(monitor = fakeMonitor)

        // Act
        val result = sut.processPid(FakeRequestConnectionPoint())

        // Assert
        assertNull(result.processPid)
        assertNull(result.processName)
    }

    @Test
    fun fileMonitor_startsMonitoringOnFileScanner() {
        // Arrange
        val fakeScanner = FakeFileScanner()
        val sut = buildSut(fileScanner = fakeScanner)
        val request = FileMonitorRequest(filePaths = listOf("a.txt"))

        // Act
        sut.fileMonitor(request) { _, _ -> }

        // Assert
        assertTrue(fakeScanner.monitoringStarted)
        assertEquals(listOf("a.txt"), fakeScanner.lastMonitorFiles)
    }

    @Test
    fun stopFileMonitor_stopsMonitoringOnFileScanner() {
        // Arrange
        val fakeScanner = FakeFileScanner()
        val sut = buildSut(fileScanner = fakeScanner)

        // Act
        sut.stopFileMonitor()

        // Assert
        assertTrue(fakeScanner.monitoringStopped)
    }

    @Test
    fun subscribeIntegrity_delegatesToFileIntegrity() {
        // Arrange
        val fakeFileIntegrity = FakeFileIntegrity()
        val sut = buildSut(fileIntegrity = fakeFileIntegrity)

        // Act
        val unsubscribe = sut.subscribeIntegrity { }

        // Assert
        assertTrue(fakeFileIntegrity.subscribeCalled)
        assertNotNull(unsubscribe)
    }

    private class FakeTpmAccess(
        private val available: Boolean = true,
        private val quoteException: Exception? = null,
    ) : TpmAccessOperations {
        var provisionAttestationKeyCalled = false

        override fun isAvailable() = available
        override fun readPCRs(pcrSelection: List<Int>) = mapOf(23 to ByteArray(32))
        override fun extendPCR(pcrIndex: Int, data: ByteArray) {}
        override fun resetPCR(pcrIndex: Int) {}
        override fun getEventLog() = byteArrayOf(0x01, 0x02, 0x03)
        override fun getEKCertificateChain() = listOf(byteArrayOf(0x04, 0x05))
        override fun provisionAttestationKey(): ByteArray {
            provisionAttestationKeyCalled = true
            return byteArrayOf(0x10, 0x20, 0x30)
        }
        override fun removeAttestationKey() {}
        override fun generateQuote(attChallengeBytes: ByteArray, pcrSelection: List<Int>): TpmQuoteResult {
            if (quoteException != null) throw quoteException
            return TpmQuoteResult(
                quote = byteArrayOf(0x01),
                signature = byteArrayOf(0x02),
                attestationKey = byteArrayOf(0x03),
            )
        }
    }

    private class FakeFileScanner : FileScannerOperations {
        var monitoringStarted = false
        var monitoringStopped = false
        var lastMonitorFiles: List<String>? = null

        override fun scanFiles(files: List<String>) = files.associateWith { "hash_$it" }
        override fun startMonitoring(files: List<String>, onModified: (String, String) -> Unit) {
            monitoringStarted = true
            lastMonitorFiles = files
        }
        override fun stopMonitoring() { monitoringStopped = true }
    }

    private class FakeProcessMonitor(
        private val running: Boolean = true,
        private val processAllowed: Boolean = true,
        private val socketPid: Int? = 1234,
        private val processName: String? = "test-process",
    ) : ProcessMonitorOperations {
        override fun isRunning(processName: String) = running
        override fun findSocketAndPid(origin: RequestConnectionPoint) = socketPid
        override fun getProcessName(pid: Int?) = processName
        override fun getProcessExecutablePath(pid: Int?) = "/usr/bin/test"
        override fun isProcessAllowed(origin: RequestConnectionPoint) = processAllowed
    }

    private class FakeFileIntegrity(
        private val intact: Boolean = true,
    ) : FileIntegrityOperations {
        var initializeCalled = false
        var lastResetIntegrity = false
        var lastVerifyFilePaths: List<String>? = null
        var subscribeCalled = false

        override fun initialize(resetIntegrity: Boolean): Map<String, String?> {
            initializeCalled = true
            lastResetIntegrity = resetIntegrity
            return emptyMap()
        }

        override fun isIntact() = intact

        override fun verifyIntegrity(filePaths: List<String>): VerifyIntegrityResponse {
            lastVerifyFilePaths = filePaths
            return VerifyIntegrityResponse(
                results = filePaths.associateWith {
                    FileIntegrityResult(path = it, expectedHash = "abc", actualHash = "abc", isValid = true)
                },
                success = true,
            )
        }

        override fun currentIntegrityState(): VerifyIntegrityResponse {
            return verifyIntegrity(emptyList())
        }

        override fun subscribe(onUpdate: (VerifyIntegrityResponse) -> Unit): () -> Unit {
            subscribeCalled = true
            return {}
        }

        override fun stopIntegrityMonitor() {}
    }

    private class FakeRequestConnectionPoint : RequestConnectionPoint {
        @Deprecated("", level = DeprecationLevel.HIDDEN)
        override val host: String = "localhost"

        @Deprecated("", level = DeprecationLevel.HIDDEN)
        override val port: Int = 8081
        override val localHost: String = "127.0.0.1"
        override val localPort: Int = 8081
        override val remoteHost: String = "127.0.0.1"
        override val remotePort: Int = 50000
        override val localAddress: String = "127.0.0.1"
        override val remoteAddress: String = "127.0.0.1"
        override val method: io.ktor.http.HttpMethod = io.ktor.http.HttpMethod.Post
        override val scheme: String = "http"
        override val version: String = "HTTP/1.1"
        override val serverHost: String = "localhost"
        override val serverPort: Int = 8081
        override val uri: String = "/attest"
    }

    private fun buildSut(
        tpm: TpmAccessOperations = FakeTpmAccess(),
        monitor: ProcessMonitorOperations = FakeProcessMonitor(),
        fileScanner: FileScannerOperations = FakeFileScanner(),
        fileIntegrity: FileIntegrityOperations = FakeFileIntegrity(),
        files: List<String> = listOf("LICENSE", "NOTICE"),
        resetFileIntegrity: Boolean = false,
    ): AttestationService {
        val config = ServiceConfig(
            files = files,
            port = 8081,
            pcrId = 23,
            enableFileIntegrity = true,
            enableQuote = true,
            enablePcrLog = true,
            enableEKCertificate = true,
            enableProcessOrigin = true,
            resetFileIntegrity = resetFileIntegrity,
        )
        return AttestationService(
            monitor = monitor,
            fileScanner = fileScanner,
            fileIntegrity = fileIntegrity,
            tpm = tpm,
            config = config,
        )
    }
}
