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

import de.gematik.zeta.sdk.attestation.service.ServiceError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttestationRequest(
    @SerialName("attestation_challenge") val attestationChallenge: String,
    @SerialName("pcr_selection") val pcrSelection: List<Int>,
)

@Serializable
data class QuoteResponse(
    val quote: ByteArray = ByteArray(0),
    val signature: ByteArray = ByteArray(0),
    val attestationKey: ByteArray = ByteArray(0),
    val error: ServiceError?,
)

data class TpmQuoteResult(
    val quote: ByteArray,
    val signature: ByteArray,
    val attestationKey: ByteArray,
)

@Serializable
data class VerifyIntegrityRequest(
    val filePaths: List<String>,
)

@Serializable
data class VerifyIntegrityResponse(
    val results: Map<String, FileIntegrityResult>,
    val success: Boolean?,
)

@Serializable
data class FileIntegrityResult(
    val path: String,
    val expectedHash: String?,
    val actualHash: String?,
    val isValid: Boolean,
)

@Serializable
data class HealthCheck(
    val status: String,
    val tpmAvaliable: Boolean,
    val processRunning: Boolean,
    val uptime: Long,
)

data class ProcessIdentity(
    val pid: Int,
    val path: String,
)

@Serializable
data class AttestationResponse(
    @SerialName("tpm_attestation_key") val tpmAttestationKey: String? = null,
    @SerialName("tpm_quote") val tpmQuote: String? = null,
    @SerialName("tpm_quote_signature") val tpmQuoteSignature: String? = null,
    @SerialName("tpm_event_log") val tpmEventLog: String? = null,
    @SerialName("tpm_ek_certificate_chain") val tpmEkCertificateChain: List<String>? = null,
    val error: ServiceError?,
)

@Serializable
data class FileMonitorRequest(
    val filePaths: List<String>,
)

@Serializable
data class FileMonitorResponse(
    val filePath: String,
    val event: String,
)

@Serializable
data class ProcessPidResponse(
    val processPid: Long?,
    val processName: String?,
)
