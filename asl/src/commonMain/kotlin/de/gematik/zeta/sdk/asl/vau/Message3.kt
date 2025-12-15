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

package de.gematik.zeta.sdk.asl.vau
import de.gematik.zeta.sdk.asl.AslHandshakeState
import de.gematik.zeta.sdk.asl.Message3
import de.gematik.zeta.sdk.asl.applyDpopFor
import de.gematik.zeta.sdk.asl.aslUrl
import de.gematik.zeta.sdk.asl.copyAuthHeadersFrom
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
internal suspend fun sendMessage3(
    request: HttpRequestBuilder,
    httpClient: ZetaHttpClient,
    cid: String,
    messageEncoded: ByteArray,
    state: AslHandshakeState,
): ByteArray {
    val dpop = state.applyDpopFor(HttpMethod.Post.value, aslUrl(request.url, cid))

    val response = httpClient
        .post(cid) {
            contentType(ContentType.Application.Cbor)
            accept(ContentType.Application.Cbor)
            setBody(messageEncoded)
            copyAuthHeadersFrom(request)
            header(HttpAuthHeaders.Dpop, dpop)
        }
    require(response.status == HttpStatusCode.OK) { "VAU: expected 200, got: ${response.status}" }
    val m4 = response.bodyAsBytes()

    return m4
}

internal fun buildMessage3(innerCipherText: ByteArray, keyConfCipherText: ByteArray): Message3 =
    Message3(
        type = "M3",
        aeadCiphertext = innerCipherText,
        aeadConfirmationCiphertext = keyConfCipherText,
    )
