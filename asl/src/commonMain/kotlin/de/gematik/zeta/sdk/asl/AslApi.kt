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
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlin.io.encoding.Base64

public interface AslApi {
    public suspend fun encrypt(request: HttpRequestBuilder, passThrough: Boolean? = false): HttpRequestBuilder
    public suspend fun decrypt(extended: ByteArray): ByteArray
}

public class AslApiImpl(
    private val resource: String,
    internal val aslProdEnvironment: Boolean,
    private val aslStorage: AslStorage,
    private val zetaHttpClient: ZetaHttpClient,
    private val accessTokenProvider: AccessTokenProvider,
    private val tpmProvider: TpmProvider,
    private val tlsValidationEnabled: Boolean = true,
) : AslApi {
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun encrypt(request: HttpRequestBuilder, passThrough: Boolean?): HttpRequestBuilder {
        val session = ensureHandshake(request)

        val innerHttp = InnerHttpCodecImpl().encodeRequest(request)
        val extended = session.encryptRequest(innerHttp)
        val bearerHeader = request.headers[HttpHeaders.Authorization]

        aslStorage.saveSession(resource, session)

        requireNotNull(session.cid) { "ASL Session has not been correctly established. CID is missing" }

        val accessToken = bearerHeader
            ?.removePrefix(HttpAuthHeaders.Dpop)
            ?.trim()
        val hash = accessToken?.let { token ->
            accessTokenProvider.hash(token)
        }
        val dpopKey = tpmProvider.generateDpopKey()
        val dpop = accessTokenProvider.createDpopToken(dpopKey.jwk, HttpMethod.Post.value, aslUrl(request.url, session.cid), null, hash)

        request.method = HttpMethod.Post
        request.url {
            takeFrom(request.url)
            encodedPath = session.cid
        }
        request.headers {
            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
            append(HttpHeaders.Accept, ContentType.Application.OctetStream.toString())
            set(HttpAuthHeaders.Dpop, dpop)
            if (!aslProdEnvironment) setTracingHeaders(session)
        }
        request.setBody(extended)

        return request
    }

    override suspend fun decrypt(extended: ByteArray): ByteArray {
        val session = aslStorage.getCurrentSession(resource)
        requireNotNull(session) { "Decryption failed: The current ASL session could not be obtained" }

        return session.decryptResponse(extended)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun ensureHandshake(request: HttpRequestBuilder): EstablishedSession {
        aslStorage.getCurrentSession(resource)?.let { return it }

        var state = AslHandshakeState.create(zetaHttpClient, request, accessTokenProvider, tpmProvider, tlsValidationEnabled)
        state = state
            .performMessage1AndReceiveMessage2()
            .processMessage2AndBuildMessage3(aslProdEnvironment)
            .sendMessage3AndReceiveMessage4()

        return state.validateMessage4AndEstablishSession(aslProdEnvironment)
    }
}

public fun HttpRequestBuilder.copyAuthHeadersFrom(request: HttpRequestBuilder) {
    val src = request.headers

    src[HttpHeaders.Authorization]?.let { value ->
        header(HttpHeaders.Authorization, value)
    }
}

private fun HeadersBuilder.setTracingHeaders(session: EstablishedSession) {
    val client2Server = Base64.encode(session.c2sAppDataKey)
    val server2Client = Base64.encode(session.s2cAppDataKey)
    append(TRACING_HEADER, "$client2Server $server2Client")
}

@OptIn(ExperimentalSerializationApi::class)
public val cbor: Cbor = Cbor {
    alwaysUseByteString = true
    useDefiniteLengthEncoding = true
    encodeDefaults = true
}
