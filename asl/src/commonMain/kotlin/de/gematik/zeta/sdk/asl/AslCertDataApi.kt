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

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public interface AslCertDataApi {
    public suspend fun fetch(certHashHex: String, cdv: Int): CertData
}

public class HttpCertDataFetcher(
    private val httpClient: ZetaHttpClient,
    private val httpRequest: HttpRequestBuilder,
) : AslCertDataApi {

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun fetch(certHashHex: String, cdv: Int): CertData {
        val url = certDataUrl(httpRequest.url, certHashHex, cdv)
        val response = httpClient.get(url) {
            accept(ContentType.Application.Cbor)
        }
        val bytes = response.body<ByteArray>()
        return cbor.decodeFromByteArray(CertData.serializer(), bytes)
    }
}

private fun certDataUrl(url: URLBuilder, certHashHex: String, cdv: Int): String =
    URLBuilder().apply {
        protocol = url.protocol
        host = url.host
        port = url.port
        encodedPath = "/CertData.$certHashHex-$cdv"
    }.buildString()

@Serializable
public data class CertData(
    @SerialName("cert") val cert: ByteArray,
    @SerialName("ca") val ca: ByteArray,
    @SerialName("rca_chain") val rcaChain: List<ByteArray>,
)
