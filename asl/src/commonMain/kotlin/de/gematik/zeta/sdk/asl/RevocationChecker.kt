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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.OcspHandler
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import kotlin.time.Clock

internal suspend fun validateRevocation(
    stapledOcspResponse: ByteArray?,
    certDer: ByteArray,
    issuerDer: ByteArray,
    ocspValidator: OcspHandler,
    httpClient: ZetaHttpClient,
    maxOcspAgeSeconds: Long = 24 * 3600,
    allowSkipForTestCertificates: Boolean,
) {
    if (stapledOcspResponse != null) {
        Log.i { "Using OCSP stapling response (${stapledOcspResponse.size} bytes)" }
        validateOcspResponse(stapledOcspResponse, certDer, issuerDer, ocspValidator, maxOcspAgeSeconds)
        return
    }

    Log.w { "No OCSP stapling - attempting fallback to direct OCSP or CRL" }

    val ocspAttempt = tryDirectOcsp(certDer, issuerDer, httpClient, ocspValidator, maxOcspAgeSeconds)
    if (ocspAttempt.success) {
        Log.i { "Successfully validated via direct OCSP" }
        return
    }

    Log.w { "Direct OCSP failed: ${ocspAttempt.error}" }

    val crlAttempt = tryDirectCrl(certDer, issuerDer, httpClient)
    if (crlAttempt.success) {
        Log.i { "Successfully validated via CRL" }
        return
    }

    Log.e { "CRL check failed: ${crlAttempt.error}" }

    if (allowSkipForTestCertificates) {
        Log.w { "Skipping revocation check for test certificate" }
        return
    }

    error(
        "Certificate revocation check failed: " +
            "No OCSP stapling, direct OCSP failed (${ocspAttempt.error}), " +
            "CRL check failed (${crlAttempt.error})",
    )
}

private data class ValidationAttempt(
    val success: Boolean,
    val error: String? = null,
)

private suspend fun tryDirectOcsp(
    certDer: ByteArray,
    issuerDer: ByteArray,
    httpClient: ZetaHttpClient,
    ocspValidator: OcspHandler,
    maxOcspAgeSeconds: Long,
): ValidationAttempt {
    return try {
        val ocspRequestData = OcspHandler().prepareOcspRequest(certDer, issuerDer)
        val ocspResponse = fetchOcspDirect(ocspRequestData.url, ocspRequestData.requestDer, httpClient)
        validateOcspResponse(ocspResponse, certDer, issuerDer, ocspValidator, maxOcspAgeSeconds)
        ValidationAttempt(success = true)
    } catch (e: Exception) {
        ValidationAttempt(success = false, error = e.message ?: "Unknown error")
    }
}

private suspend fun tryDirectCrl(
    certDer: ByteArray,
    issuerDer: ByteArray,
    httpClient: ZetaHttpClient,
): ValidationAttempt {
    return try {
        val crlUrl = OcspHandler().extractCrlUrl(certDer)
            ?: return ValidationAttempt(success = false, error = "No CRL URL in certificate")

        Log.i { "Fetching CRL from: $crlUrl" }
        val crlDer = httpClient.get(crlUrl).bodyAsBytes()
        Log.i { "CRL fetched: ${crlDer.size} bytes" }

        OcspHandler().validateCrl(crlDer, certDer, issuerDer)
        ValidationAttempt(success = true)
    } catch (e: Exception) {
        ValidationAttempt(success = false, error = e.message ?: "Unknown error")
    }
}

private fun validateOcspResponse(
    ocspResponse: ByteArray,
    certDer: ByteArray,
    issuerDer: ByteArray,
    ocspValidator: OcspHandler,
    maxOcspAgeSeconds: Long,
) {
    val producedAt = ocspValidator.getProducedAtEpochSeconds(ocspResponse)
    val ageSeconds = Clock.System.now().epochSeconds - producedAt
    Log.i { "OCSP response age: ${ageSeconds}s" }

    require(ageSeconds <= maxOcspAgeSeconds) {
        "OCSP response too old: ${ageSeconds / 3600}h (max ${maxOcspAgeSeconds / 3600}h)"
    }

    ocspValidator.validate(ocspResponse, certDer, issuerDer)
}
