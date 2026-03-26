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

package de.gematik.zeta.sdk.attestation

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.model.AttestationStatus
import de.gematik.zeta.sdk.attestation.model.AttestationStatusCallback
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

fun interface AttestationService {
    suspend fun generateAttestation(attestationChallenge: String): AttestationResponse
}

data class TpmAttestationServiceConfig(
    val attestationEndpoint: String,
    val pcrSelection: List<Int> = listOf(23),
    val websocketEndpoint: String?,
    val statusCallback: AttestationStatusCallback?,
)

class AttestationServiceApi(
    private val config: TpmAttestationServiceConfig,
    private val zetaHttpClient: ZetaHttpClient,
) : AttestationService {
    @Volatile
    private var currentStatus: AttestationStatus = AttestationStatus.OK
    private var websocketJob: Job? = null

    init {
        config.websocketEndpoint?.let { wsEndpoint ->
            websocketJob = CoroutineScope(Dispatchers.IO).launch {
                connectToStatusWebSocket(wsEndpoint)
            }
        } ?: run {
            updateStatus(AttestationStatus.OK)
        }
    }

    override suspend fun generateAttestation(attestationChallenge: String): AttestationResponse {
        try {
            val request = AttestationRequest(
                attestationChallenge = attestationChallenge,
                pcrSelection = config.pcrSelection,
            )

            val response: AttestationResponse = zetaHttpClient.post(config.attestationEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            when {
                response.error != null -> {
                    val errorMsg = when (response.error.code) {
                        ErrorCode.TPM_NOT_AVAILABLE -> "TPM hardware not available"
                        ErrorCode.TPM_QUOTE_ERROR -> "TPM quote generation failed"
                        ErrorCode.INVALID_ARGUMENT -> "Invalid attestation parameters"
                        ErrorCode.INTERNAL_ERROR -> "Internal attestation service error"
                        ErrorCode.PROCESS_NOT_ALLOWED -> "Internal attestation service error"
                    }
                    Log.e { "Attestation error: ${response.error.message}" }
                    updateStatus(AttestationStatus.KO("$errorMsg: ${response.error.message}"))
                }

                response.tpmQuote.isEmpty() -> {
                    Log.e { "Attestation response missing TPM quote" }
                    updateStatus(AttestationStatus.KO("Missing TPM quote in response"))
                }

                else -> {
                    Log.i { "Attestation generated successfully" }
                }
            }

            return response
        } catch (e: Exception) {
            Log.e { "Attestation service failed: ${e.message}" }
            return AttestationResponse(
                error =
                ServiceError(ErrorCode.INTERNAL_ERROR, "Failed to call TPM attestation service: ${e.message}"),
            )
        }
    }

    @Serializable
    internal data class VerifyIntegrityResponse(
        val results: Map<String, FileIntegrityResult>,
        val success: Boolean,
    )

    @Serializable
    data class FileIntegrityResult(
        val path: String,
        val expectedHash: String?,
        val actualHash: String?,
        val isValid: Boolean,
    )

    private suspend fun connectToStatusWebSocket(endpoint: String) {
        try {
            zetaHttpClient.webSocket(request = {
                url(endpoint)
                header(HttpHeaders.Accept, "application/json")
            }) {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val response = Json.decodeFromString<VerifyIntegrityResponse>(frame.readText())
                            when {
                                response.success -> {
                                    Log.d { "Integrity check passed" }
                                    updateStatus(AttestationStatus.OK)
                                }

                                else -> {
                                    val failedFiles = response.results
                                        .filter { !it.value.isValid }
                                        .map { it.key }

                                    val reason = buildString {
                                        append("Integrity check failed for ${failedFiles.size} file(s): ")
                                        append(failedFiles.joinToString(", ") { it.substringAfterLast('/') })
                                    }

                                    Log.w { reason }
                                    response.results.forEach { (path, result) ->
                                        if (!result.isValid) {
                                            Log.w { "$path: expected=${result.expectedHash?.take(16)}..., actual=${result.actualHash?.take(16)}..." }
                                        }
                                    }

                                    updateStatus(AttestationStatus.KO(reason))
                                }
                            }
                        }

                        else -> {
                            Log.i { "Ignoring other frames" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e { "WebSocket connection failed: ${e.message}" }
            updateStatus(AttestationStatus.Degraded("WebSocket connection failed: ${e.message}"))
            delay(5000)
            connectToStatusWebSocket(endpoint)
        }
    }

    private fun updateStatus(status: AttestationStatus) {
        if (currentStatus != status) {
            Log.d { "Attestation status changed: ${currentStatus::class.simpleName} -> ${status::class.simpleName}" }
            when (status) {
                is AttestationStatus.OK -> Log.i { "Status: OK" }
                is AttestationStatus.Degraded -> Log.w { "Status: DEGRADED - ${status.reason}" }
                is AttestationStatus.KO -> Log.e { "Status: FAILED - ${status.reason}" }
            }
            currentStatus = status
            config.statusCallback?.onStatusChange(status)
        }
    }

    fun close() {
        websocketJob?.cancel()
    }
}

@Serializable
data class AttestationRequest(
    @SerialName("attestation_challenge")
    val attestationChallenge: String,

    @SerialName("pcr_selection")
    val pcrSelection: List<Int>,
)

@Serializable
data class AttestationResponse(
    @SerialName("tpm_quote")
    val tpmQuote: String = "",

    @SerialName("tpm_quote_signature")
    val tpmQuoteSignature: String = "",

    @SerialName("tpm_attestation_key")
    val attestationKey: String = "",

    @SerialName("tpm_event_log")
    val eventLog: String? = null,

    @SerialName("tpm_certificate_chain")
    val certificateChain: List<String> = emptyList(),
    val error: ServiceError? = null,
)

@Serializable
data class ServiceError(
    val code: ErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

enum class ErrorCode {
    TPM_NOT_AVAILABLE,
    TPM_QUOTE_ERROR,
    INVALID_ARGUMENT,
    INTERNAL_ERROR,
    PROCESS_NOT_ALLOWED,
}
