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

package de.gematik.zeta.sdk.attestation.model

import de.gematik.zeta.sdk.attestation.service.ErrorCode
import de.gematik.zeta.sdk.attestation.service.ServiceError
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttestationModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun attestationRequest_serializesCorrectly() {
        // Arrange
        val request = AttestationRequest(
            attestationChallenge = "dGVzdA==",
            pcrSelection = listOf(0, 1, 7),
        )

        // Act
        val encoded = json.encodeToString(AttestationRequest.serializer(), request)

        // Assert
        assertTrue(encoded.contains("\"attestation_challenge\":\"dGVzdA==\""))
        assertTrue(encoded.contains("\"pcr_selection\":[0,1,7]"))
    }

    @Test
    fun attestationRequest_deserializesCorrectly() {
        // Arrange
        val jsonStr = """{"attestation_challenge":"Y2hhbGxlbmdl","pcr_selection":[0,7,23]}"""

        // Act
        val request = json.decodeFromString(AttestationRequest.serializer(), jsonStr)

        // Assert
        assertEquals("Y2hhbGxlbmdl", request.attestationChallenge)
        assertEquals(listOf(0, 7, 23), request.pcrSelection)
    }

    @Test
    fun verifyIntegrityRequest_serializesCorrectly() {
        // Arrange
        val request = VerifyIntegrityRequest(filePaths = listOf("/etc/hosts", "/usr/bin/app"))

        // Act
        val encoded = json.encodeToString(VerifyIntegrityRequest.serializer(), request)

        // Assert
        assertTrue(encoded.contains("\"/etc/hosts\""))
        assertTrue(encoded.contains("\"/usr/bin/app\""))
    }

    @Test
    fun verifyIntegrityRequest_deserializesCorrectly() {
        // Arrange
        val jsonStr = """{"filePaths":["/a","/b"]}"""

        // Act
        val request = json.decodeFromString(VerifyIntegrityRequest.serializer(), jsonStr)

        // Assert
        assertEquals(listOf("/a", "/b"), request.filePaths)
    }

    @Test
    fun verifyIntegrityResponse_serializesWithResults() {
        // Arrange
        val response = VerifyIntegrityResponse(
            results = mapOf(
                "/a" to FileIntegrityResult("/a", "abc", "abc", true),
                "/b" to FileIntegrityResult("/b", "def", "xyz", false),
            ),
            success = false,
        )

        // Act
        val encoded = json.encodeToString(VerifyIntegrityResponse.serializer(), response)

        // Assert
        assertTrue(encoded.contains("\"success\":false"))
        assertTrue(encoded.contains("\"isValid\":true"))
        assertTrue(encoded.contains("\"isValid\":false"))
    }

    @Test
    fun fileIntegrityResult_withNullHashes() {
        // Arrange
        val result = FileIntegrityResult(
            path = "/missing",
            expectedHash = null,
            actualHash = null,
            isValid = true,
        )

        // Act
        val encoded = json.encodeToString(FileIntegrityResult.serializer(), result)
        val decoded = json.decodeFromString(FileIntegrityResult.serializer(), encoded)

        // Assert
        assertNull(decoded.expectedHash)
        assertNull(decoded.actualHash)
        assertEquals("/missing", decoded.path)
    }

    @Test
    fun healthCheck_serializesCorrectly() {
        // Arrange
        val health = HealthCheck(
            status = "OK",
            tpmAvaliable = true,
            processRunning = true,
            uptime = 120L,
        )

        // Act
        val encoded = json.encodeToString(HealthCheck.serializer(), health)
        val decoded = json.decodeFromString(HealthCheck.serializer(), encoded)

        // Assert
        assertEquals("OK", decoded.status)
        assertEquals(true, decoded.tpmAvaliable)
        assertEquals(true, decoded.processRunning)
        assertEquals(120L, decoded.uptime)
    }

    @Test
    fun attestationResponse_defaultValuesAreNull() {
        // Arrange & Act
        val response = AttestationResponse(error = null)

        // Assert
        assertEquals(null, response.tpmAttestationKey)
        assertEquals(null, response.tpmQuote)
        assertEquals(null, response.tpmQuoteSignature)
        assertEquals(null, response.tpmEventLog)
        assertTrue(response.tpmEkCertificateChain.isNullOrEmpty())
        assertNull(response.error)
    }

    @Test
    fun attestationResponse_withError() {
        // Arrange
        val error = ServiceError(ErrorCode.TPM_NOT_AVAILABLE, "TPM not found")
        val response = AttestationResponse(error = error)

        // Act
        val encoded = json.encodeToString(AttestationResponse.serializer(), response)
        val decoded = json.decodeFromString(AttestationResponse.serializer(), encoded)

        // Assert
        assertEquals(ErrorCode.TPM_NOT_AVAILABLE, decoded.error?.code)
        assertEquals("TPM not found", decoded.error?.message)
    }

    @Test
    fun attestationResponse_serializesWithData() {
        // Arrange
        val response = AttestationResponse(
            tpmAttestationKey = "ak-key",
            tpmQuote = "quote-data",
            tpmQuoteSignature = "sig-data",
            tpmEventLog = "event-log",
            tpmEkCertificateChain = listOf("cert1", "cert2"),
            error = null,
        )

        // Act
        val encoded = json.encodeToString(AttestationResponse.serializer(), response)

        // Assert
        assertTrue(encoded.contains("\"tpm_attestation_key\":\"ak-key\""))
        assertTrue(encoded.contains("\"tpm_quote\":\"quote-data\""))
        assertTrue(encoded.contains("\"tpm_quote_signature\":\"sig-data\""))
        assertTrue(encoded.contains("\"tpm_event_log\":\"event-log\""))
    }

    @Test
    fun fileMonitorRequest_roundTrip() {
        // Arrange
        val request = FileMonitorRequest(filePaths = listOf("/etc/hosts"))

        // Act
        val encoded = json.encodeToString(FileMonitorRequest.serializer(), request)
        val decoded = json.decodeFromString(FileMonitorRequest.serializer(), encoded)

        // Assert
        assertEquals(listOf("/etc/hosts"), decoded.filePaths)
    }

    @Test
    fun fileMonitorResponse_roundTrip() {
        // Arrange
        val response = FileMonitorResponse(filePath = "/etc/hosts", event = "MODIFIED")

        // Act
        val encoded = json.encodeToString(FileMonitorResponse.serializer(), response)
        val decoded = json.decodeFromString(FileMonitorResponse.serializer(), encoded)

        // Assert
        assertEquals("/etc/hosts", decoded.filePath)
        assertEquals("MODIFIED", decoded.event)
    }

    @Test
    fun processPidResponse_withValues() {
        // Arrange
        val response = ProcessPidResponse(processPid = 1234L, processName = "myApp")

        // Act
        val encoded = json.encodeToString(ProcessPidResponse.serializer(), response)
        val decoded = json.decodeFromString(ProcessPidResponse.serializer(), encoded)

        // Assert
        assertEquals(1234L, decoded.processPid)
        assertEquals("myApp", decoded.processName)
    }

    @Test
    fun processPidResponse_withNullValues() {
        // Arrange
        val response = ProcessPidResponse(processPid = null, processName = null)

        // Act
        val encoded = json.encodeToString(ProcessPidResponse.serializer(), response)
        val decoded = json.decodeFromString(ProcessPidResponse.serializer(), encoded)

        // Assert
        assertNull(decoded.processPid)
        assertNull(decoded.processName)
    }

    @Test
    fun processIdentity_storesValues() {
        // Arrange & Act
        val identity = ProcessIdentity(pid = 42, path = "/usr/bin/app")

        // Assert
        assertEquals(42, identity.pid)
        assertEquals("/usr/bin/app", identity.path)
    }

    @Test
    fun tpmQuoteResult_storesValues() {
        // Arrange
        val quote = byteArrayOf(1, 2, 3)
        val signature = byteArrayOf(4, 5, 6)
        val ak = byteArrayOf(7, 8, 9)

        // Act
        val result = TpmQuoteResult(quote = quote, signature = signature, attestationKey = ak)

        // Assert
        assertTrue(quote.contentEquals(result.quote))
        assertTrue(signature.contentEquals(result.signature))
        assertTrue(ak.contentEquals(result.attestationKey))
    }
}
