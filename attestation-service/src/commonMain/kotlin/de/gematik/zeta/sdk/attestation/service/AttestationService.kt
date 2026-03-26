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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.interfaces.FileIntegrityOperations
import de.gematik.zeta.sdk.attestation.interfaces.FileScannerOperations
import de.gematik.zeta.sdk.attestation.interfaces.ProcessMonitorOperations
import de.gematik.zeta.sdk.attestation.model.AttestationRequest
import de.gematik.zeta.sdk.attestation.model.AttestationResponse
import de.gematik.zeta.sdk.attestation.model.FileMonitorRequest
import de.gematik.zeta.sdk.attestation.model.HealthCheck
import de.gematik.zeta.sdk.attestation.model.ProcessPidResponse
import de.gematik.zeta.sdk.attestation.model.QuoteResponse
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityRequest
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityResponse
import de.gematik.zeta.sdk.attestation.service.ErrorCode
import de.gematik.zeta.sdk.attestation.service.ServiceError
import de.gematik.zeta.sdk.attestation.service.TpmException
import de.gematik.zeta.sdk.attestation.tpm.TpmAccessOperations
import io.ktor.http.RequestConnectionPoint
import kotlin.io.encoding.Base64
import kotlin.time.Clock

class AttestationService(
    private val monitor: ProcessMonitorOperations,
    private val fileScanner: FileScannerOperations,
    private val fileIntegrity: FileIntegrityOperations,
    private val tpm: TpmAccessOperations,
    private val config: ServiceConfig,
) {
    private val startTime = Clock.System.now()

    fun initialize() {
        if (config.enableQuote) {
            val akPubKey = tpm.provisionAttestationKey()
            Log.i { "AK initialized, size: ${akPubKey.size}" }
        }
        if (config.enableFileIntegrity) {
            fileIntegrity.initialize(config.resetFileIntegrity)
        }
    }

    fun buildAttestationResponse(
        request: AttestationRequest,
        origin: RequestConnectionPoint,
    ): AttestationResponse {
        var akPubKeyPem: String? = null
        var quoteBase64: String? = null
        var quoteSignatureBase64: String? = null
        if (config.enableQuote) {
            val quoteResult = getQuote(request.attestationChallenge, request.pcrSelection, origin)
            if (quoteResult.error != null) {
                return AttestationResponse(error = quoteResult.error)
            }
            akPubKeyPem = b64UrlNoPadding().encode(quoteResult.attestationKey)
            quoteBase64 = b64UrlNoPadding().encode(quoteResult.quote)
            quoteSignatureBase64 = b64UrlNoPadding().encode(quoteResult.signature)
        }

        var eventLogBase64: String? = null
        if (config.enablePcrLog) {
            val eventLog = tpm.getEventLog()
            eventLogBase64 = b64UrlNoPadding().encode(eventLog)
        }

        var ekCertsPem: List<String>? = null
        if (config.enableEKCertificate) {
            try {
                Log.i { "Reading EK certificate chain" }
                val ekCerts = tpm.getEKCertificateChain()

                if (ekCerts.isEmpty()) {
                    Log.w { "WARNING: No EK certificates found in TPM" }
                } else {
                    Log.i { "Found ${ekCerts.size} EK certificate(s)" }
                    ekCerts.forEachIndexed { index, cert ->
                        Log.i { "Certificate $index: ${cert.size} bytes" }
                    }
                }

                ekCertsPem = ekCerts.map { cert ->
                    val encoded = b64UrlNoPadding().encode(cert)
                    Log.i { "Encoded cert (first 50 chars): ${encoded.take(50)}..." }
                    encoded
                }
            } catch (e: Exception) {
                Log.e { "ERROR reading EK certificates: ${e.message}" }
                ekCertsPem = null
            }
        } else {
            Log.i { "EK certificate reading DISABLED in config" }
        }

        return AttestationResponse(
            tpmAttestationKey = akPubKeyPem,
            tpmQuote = quoteBase64,
            tpmQuoteSignature = quoteSignatureBase64,
            tpmEventLog = eventLogBase64,
            tpmEkCertificateChain = ekCertsPem,
            error = null,
        )
    }

    private fun b64UrlNoPadding(): Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private fun getQuote(attestationChallenge: String, pcrSelection: List<Int>, origin: RequestConnectionPoint): QuoteResponse {
        if (attestationChallenge.isEmpty()) {
            return QuoteResponse(error = ServiceError(ErrorCode.INVALID_ARGUMENT, "attestationChallenge is empty"))
        }

        if (pcrSelection.isEmpty()) {
            return QuoteResponse(error = ServiceError(ErrorCode.INVALID_ARGUMENT, "prcSelection is empty"))
        }

        val attChallengeBytes = try {
            Base64.decode(attestationChallenge)
        } catch (_: IllegalArgumentException) {
            return QuoteResponse(error = ServiceError(code = ErrorCode.INVALID_ARGUMENT, message = "attestationChallenge must be base64"))
        }

        if (!tpm.isAvailable()) {
            return QuoteResponse(error = ServiceError(ErrorCode.TPM_NOT_AVAILABLE, "TPM not available"))
        }

        if (config.enableFileIntegrity) {
            if (!fileIntegrity.isIntact()) {
                return QuoteResponse(error = ServiceError(ErrorCode.INTERNAL_ERROR, "Filesystem integrity violated"))
            }
        }

        if (config.enableProcessOrigin) {
            if (!monitor.isProcessAllowed(origin)) {
                return QuoteResponse(error = ServiceError(ErrorCode.PROCESS_NOT_ALLOWED, "Client process not allowed"))
            }
        }

        return try {
            val result = tpm.generateQuote(
                attChallengeBytes = attChallengeBytes,
                pcrSelection = pcrSelection,
            )

            QuoteResponse(
                quote = result.quote,
                signature = result.signature,
                attestationKey = result.attestationKey,
                error = null,
            )
        } catch (tpmEx: TpmException) {
            QuoteResponse(error = ServiceError(code = ErrorCode.TPM_QUOTE_ERROR, message = tpmEx.message ?: "Unexpected error"))
        } catch (e: Exception) {
            Log.e { "Unexpected error: ${e.message}" }
            QuoteResponse(error = ServiceError(code = ErrorCode.INTERNAL_ERROR, message = "Internal error"))
        }
    }

    fun verifyIntegrity(request: VerifyIntegrityRequest): VerifyIntegrityResponse {
        return fileIntegrity.verifyIntegrity(request.filePaths)
    }

    fun currentIntegrityState(): VerifyIntegrityResponse {
        return fileIntegrity.verifyIntegrity(config.files)
    }

    fun subscribeIntegrity(onUpdate: (VerifyIntegrityResponse) -> Unit): () -> Unit {
        return fileIntegrity.subscribe(onUpdate)
    }

    fun health(): HealthCheck {
        val uptime = (Clock.System.now() - startTime).inWholeSeconds

        return HealthCheck(
            status = "OK",
            tpmAvaliable = tpm.isAvailable(),
            processRunning = monitor.isRunning("attestation-service"),
            uptime = uptime,
        )
    }

    fun processPid(origin: RequestConnectionPoint): ProcessPidResponse {
        Log.i { "processPid: local = ${origin.localHost}:${origin.localPort}, remote = ${origin.remoteHost}:${origin.remotePort}" }
        val processPid = monitor.findSocketAndPid(origin)
        val processName = monitor.getProcessName(processPid)
        return ProcessPidResponse(processPid?.toLong(), processName)
    }

    fun fileMonitor(request: FileMonitorRequest, onModified: (String, String) -> Unit) {
        val files = request.filePaths
        fileScanner.startMonitoring(files) { file, event ->
            Log.i { "onModified: $file, $event" }
            onModified(file, event)
        }
    }

    fun stopFileMonitor() {
        fileScanner.stopMonitoring()
    }
}

data class ServiceConfig(
    val files: List<String>,
    val port: Int,
    val pcrId: Int,
    val resetFileIntegrity: Boolean,
    val enableFileIntegrity: Boolean,
    val enableQuote: Boolean,
    val enablePcrLog: Boolean,
    val enableEKCertificate: Boolean,
    val enableProcessOrigin: Boolean,
    val allowedExecutables: List<String> = emptyList(),
)
