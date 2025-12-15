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
import de.gematik.zeta.sdk.asl.Message1
import de.gematik.zeta.sdk.asl.Message1Bundle
import de.gematik.zeta.sdk.asl.Message1Result
import de.gematik.zeta.sdk.asl.VauPairKeys
import de.gematik.zeta.sdk.asl.applyDpopFor
import de.gematik.zeta.sdk.asl.aslUrl
import de.gematik.zeta.sdk.asl.cbor
import de.gematik.zeta.sdk.asl.copyAuthHeadersFrom
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.crypto.EcPointP256
import de.gematik.zeta.sdk.crypto.Kem
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray

// Der Client erzeugt die ECDH und Kyber KeyPairs. Diese werden in Message 1 gepackt und zum Server geschickt.
@OptIn(ExperimentalSerializationApi::class)
internal fun buildMessage1(mlKemKeyGenerator: Kem, ecdhKeyGenerator: Kem): Message1Bundle {
    val mlKem = mlKemKeyGenerator.generateKeys()
    val ec = ecdhKeyGenerator.generateKeys()

    requireNotNull(ec.sec1) { "SEC1 is required to create Message 1" }

    val (x, y) = sec1ToXY(ec.sec1!!)

    val message = Message1(
        type = "M1",
        ecdhPublicKey = EcPointP256("P-256", x, y),
        mlKemPublicKey = mlKem.skpi,
    )

    val bytes = cbor.encodeToByteArray(message)
    val keys = VauPairKeys(ec, mlKem)

    return Message1Bundle(bytes, keys)
}

internal suspend fun sendMessage1AndReceiveMessage2(
    request: HttpRequestBuilder,
    messageEncoded: ByteArray,
    httpClient: ZetaHttpClient,
    state: AslHandshakeState,
): Message1Result {
    val dpop = state.applyDpopFor(HttpMethod.Post.value, aslUrl(request.url))

    val response = httpClient
        .post("/ASL") {
            contentType(ContentType.Application.Cbor)
            accept(ContentType.Application.Cbor)
            setBody(messageEncoded)
            copyAuthHeadersFrom(request)
            header(HttpAuthHeaders.Dpop, dpop)
        }

    require(response.status == HttpStatusCode.OK) { "VAU: expected 200, got: ${response.status}" }

    val cid = getCid(response.raw.headers)

    val message1Result = response.bodyAsBytes()
    val transcriptAfter = messageEncoded + message1Result

    return Message1Result(
        response = message1Result,
        cid = cid,
        transcript = transcriptAfter,
    )
}

private fun getCid(headers: Headers): String {
    val ct = headers[HttpHeaders.ContentType]?.lowercase() ?: ""
    require(ct.startsWith("application/cbor")) { "Expected application/cbor, got: $ct" }

    val cid = headers["zeta-asl-cid"] ?: error("Missing zeta-asl-cid header")
    require(cid.length <= 200) { "Invalid zeta-asl-cid length" }
    require(cid.startsWith("/")) { "zeta-asl-cid must start with '/'" }
    require(cid.all { it.isLetterOrDigit() || it == '/' || it == '-' }) {
        "VAU-CID contains invalid characters"
    }
    return cid
}

// A_24425-01 - VAU-Protokoll: VAU-Schlüssel für die VAU-Protokoll-Schlüsselaushandlung
// "x" : Binärwert-x-Koordinate-32-Byte-big-endian (256 Bit),
// "y" : Binärwert-x-Koordinate-32-Byte-big-endian (256 Bit),
public fun sec1ToXY(sec1: ByteArray): Pair<ByteArray, ByteArray> {
    require(sec1.size == 65 && sec1[0] == 0x04.toByte()) { "SEC uncompressed required" }
    return sec1.copyOfRange(1, 33) to sec1.copyOfRange(33, 65)
}
