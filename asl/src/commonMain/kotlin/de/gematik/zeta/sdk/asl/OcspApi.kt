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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody

internal suspend fun fetchOcspDirect(
    url: String,
    requestDer: ByteArray,
    httpClient: ZetaHttpClient,
): ByteArray {
    Log.i { "Fetching OCSP from: $url (${requestDer.size} bytes)" }

    val response = httpClient.post(url) {
        header("Content-Type", "application/ocsp-request")
        header("Accept", "application/ocsp-response")
        setBody(requestDer)
    }

    val responseBytes = response.bodyAsBytes()
    Log.i { "OCSP response received: ${responseBytes.size} bytes" }

    if (responseBytes.size < 100) {
        val hexDump = responseBytes.toHexString()
        Log.w { "Small OCSP response hex: $hexDump" }
    }

    if (responseBytes.size < 20) {
        error(
            "OCSP response too small (${responseBytes.size} bytes). " +
                "Likely an error response from the OCSP server.",
        )
    }

    return responseBytes
}
