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

import de.gematik.zeta.sdk.asl.vau.buildMessage1
import de.gematik.zeta.sdk.asl.vau.buildMessage3
import de.gematik.zeta.sdk.asl.vau.computeTranscriptHash
import de.gematik.zeta.sdk.asl.vau.encryptKeyConfirmation
import de.gematik.zeta.sdk.asl.vau.processMessage2AndDeriveMessage3
import de.gematik.zeta.sdk.asl.vau.sendMessage1AndReceiveMessage2
import de.gematik.zeta.sdk.asl.vau.sendMessage3
import de.gematik.zeta.sdk.asl.vau.validateMessage4AndFinalizeSession
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.crypto.EcdhP256Kem
import de.gematik.zeta.sdk.crypto.ML768Kem
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.serialization.ExperimentalSerializationApi

public data class AslHandshakeState(
    val request: HttpRequestBuilder,
    val httpClient: ZetaHttpClient,
    val mlKem: ML768Kem,
    val ecdhKem: EcdhP256Kem,
    val message1: Message1Bundle? = null,
    val message1Result: Message1Result? = null,
    val m3Result: Message3Result? = null,
    val m3Encoded: ByteArray? = null,
    val transcriptHash: ByteArray? = null,
    val message4: Message4? = null,
    val accessTokenProvider: AccessTokenProvider,

) {
    public companion object {
        public fun create(
            httpClientBuilder: ZetaHttpClientBuilder,
            request: HttpRequestBuilder,
            accessTokenProvider: AccessTokenProvider,
        ): AslHandshakeState {
            val httpClient = httpClientBuilder.build(aslUrl(request.url))

            return AslHandshakeState(
                request = request,
                httpClient = httpClient,
                mlKem = ML768Kem(),
                ecdhKem = EcdhP256Kem(),
                accessTokenProvider = accessTokenProvider,
            )
        }
    }
}

public suspend fun AslHandshakeState.performMessage1AndReceiveMessage2(): AslHandshakeState {
    val m1 = buildMessage1(mlKem, ecdhKem)

    val result = sendMessage1AndReceiveMessage2(
        request = request,
        messageEncoded = m1.encoded,
        httpClient = httpClient,
        state = this,
    )

    return copy(
        message1 = m1,
        message1Result = result,
    )
}

@OptIn(ExperimentalSerializationApi::class)
public fun AslHandshakeState.processMessage2AndBuildMessage3(): AslHandshakeState {
    requireNotNull(message1) { "Message 1 must be present before processing Message 2" }
    requireNotNull(message1Result) { "Message 1 result must be present before processing Message 2" }

    val m3Result = processMessage2AndDeriveMessage3(message1, message1Result, mlKem, ecdhKem)

    val keyConfCipherText = encryptKeyConfirmation(
        m3Result.k2.clientToServerConfirmationKey,
        m3Result.expectedTranscriptHash,
    )

    val m3 = buildMessage3(m3Result.m3Encoded, keyConfCipherText)
    val m3Encoded = cbor.encodeToByteArray(Message3.serializer(), m3)

    val transcriptHash = computeTranscriptHash(
        m1 = message1.encoded,
        m2 = message1Result.response,
        m3 = m3Encoded,
    )

    return copy(
        m3Result = m3Result,
        m3Encoded = m3Encoded,
        transcriptHash = transcriptHash,
    )
}

@OptIn(ExperimentalSerializationApi::class)
public suspend fun AslHandshakeState.sendMessage3AndReceiveMessage4(): AslHandshakeState {
    val m1Result = requireNotNull(message1Result) { "Message 1 result must be present before sending Message 3" }
    requireNotNull(m3Encoded) { "Message 3 must be encoded before sending" }

    val result = sendMessage3(
        request = request,
        httpClient = httpClient,
        cid = m1Result.cid,
        messageEncoded = m3Encoded,
        state = this,
    )

    val m4 = cbor.decodeFromByteArray(Message4.serializer(), result)

    return copy(message4 = m4)
}

public fun AslHandshakeState.validateMessage4AndEstablishSession(): EstablishedSession {
    requireNotNull(m3Result) { "M3 result must be present before validating Message 4" }
    requireNotNull(message4) { "Message 4 must be present before validation" }
    requireNotNull(transcriptHash) { "Transcript hash must be present before validating Message 4" }

    validateMessage4AndFinalizeSession(message4, m3Result, transcriptHash)

    val m1Result = requireNotNull(message1Result) { "Message 1 result must be present to build EstablishedSession" }

    return EstablishedSession(
        m3Result.k2.keyId,
        m3Result.k2.clientToServerAppDataKey,
        m3Result.k2.serverToClientAppDataKey,
        m1Result.cid,
    )
}

public suspend fun AslHandshakeState.applyDpopFor(method: String, targetUrl: String): String {
    val auth = request.headers[HttpHeaders.Authorization] ?: error("Missing auth header")
    val token = auth.removePrefix("Bearer").trim()

    val hashed = accessTokenProvider.hash(token)
    return accessTokenProvider.createDpopToken(method, targetUrl, null, hashed)
}

public fun aslUrl(url: URLBuilder, cid: String? = null): String {
    return URLBuilder().apply {
        protocol = url.protocol
        host = url.host
        port = url.port
        encodedPath = cid ?: "/ASL"
    }.buildString()
}

public fun getTracingHeader(req: HttpRequestBuilder): String? {
    return if (req.headers.contains(ZETA_ASL_TRACING)) {
        req.headers[ZETA_ASL_TRACING].toString()
    } else {
        null
    }
}
