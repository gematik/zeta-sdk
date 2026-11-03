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

package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.logging.Log
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

public data class HttpResponseWrapper(val status: Int, val headers: Map<String, String>, val body: String) {
    public companion object {
        public suspend fun from(response: HttpResponse): HttpResponseWrapper {
            val bodyBytes = response.body<ByteArray>()
            val effective = extractEffectiveAttrs(response)

            return HttpResponseWrapper(
                status = effective.status,
                headers = effective.headers,
                body = bodyBytes.decodeToString(),
            )
        }
    }
}

private class EffectiveResponseAttrs(
    val status: Int,
    val headers: Map<String, String>,
)

private fun extractEffectiveAttrs(response: HttpResponse): EffectiveResponseAttrs {
    val attrs = response.call.attributes

    val effectiveStatus: Int =
        attrs.getOrNull(InnerStatusKey) ?: response.status.value

    val effectiveHeaders: Map<String, String> =
        attrs.getOrNull(InnerHeadersKey)
            ?: response.headers.entries().associate { (k, values) ->
                k to values.joinToString(",")
            }

    return EffectiveResponseAttrs(effectiveStatus, effectiveHeaders)
}

public suspend fun toZetaResponse(response: HttpResponse): ZetaHttpResponse {
    response.body<ByteArray>()
    val attrs = response.call.attributes
    Log.d { "Available attributes: ${attrs.allKeys}" }
    Log.d { "Has InnerStatusKey? ${attrs.contains(InnerStatusKey)}" }

    val effective = extractEffectiveAttrs(response)
    Log.d { "Effective status: ${effective.status}, Raw status: ${response.status.value}" }

    return ZetaHttpResponse(
        response,
        effective.headers,
        HttpStatusCode.fromValue(effective.status),
    )
}
