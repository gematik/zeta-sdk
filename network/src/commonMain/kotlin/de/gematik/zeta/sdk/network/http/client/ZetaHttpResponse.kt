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
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

public val InnerStatusKey: AttributeKey<Int> =
    AttributeKey("asl-inner-status")

public val InnerHeadersKey: AttributeKey<Map<String, String>> =
    AttributeKey("asl-inner-headers")

public class ZetaHttpResponse internal constructor(
    public val raw: HttpResponse,
    public val headers: Map<String, String>,
    public val status: HttpStatusCode,
) {
    public suspend inline fun <reified T> body(): T {
        if (isPlainResponse()) {
            return raw.body()
        }

        val bytes = bodyAsBytes()
        if (bytes.isEmpty()) {
            return when (T::class) {
                ByteArray::class -> ByteArray(0) as T
                String::class -> "" as T
                Unit::class -> Unit as T
                else -> error("No body for ${T::class.simpleName}")
            }
        }

        if (T::class == ByteArray::class) {
            return bytes as T
        }

        if (T::class == String::class) {
            return bytes.decodeToString() as T
        }

        val deserializer = zetaJson.serializersModule.serializer<T>()
        return zetaJson.decodeFromString(deserializer, bytes.decodeToString())
    }

    public suspend inline fun bodyAsText(): String {
        return if (isPlainResponse()) {
            raw.bodyAsText()
        } else {
            bodyAsBytes().decodeToString()
        }
    }

    public suspend inline fun bodyAsBytes(): ByteArray = raw.bodyAsBytes()

    public fun isPlainResponse(): Boolean {
        val path = raw.call.response.request.url.encodedPath
        val ct = raw.call.response.headers[HttpHeaders.ContentType]
        return !isAslEncryptedResponse(path, ct)
    }
}

public suspend fun HttpResponse.toZetaResponse(): ZetaHttpResponse {
    this.body<ByteArray>()
    val attrs = call.attributes

    Log.d { "Available attributes: ${attrs.allKeys}" }
    Log.d { "Has InnerStatusKey? ${attrs.contains(InnerStatusKey)}" }

    val effectiveStatus: Int =
        attrs.getOrNull(InnerStatusKey) ?: status.value

    Log.d { "Effective status: $effectiveStatus, Raw status: ${status.value}" }

    val effectiveHeaders: Map<String, String> =
        attrs.getOrNull(InnerHeadersKey)
            ?: headers.entries().associate { (k, values) ->
                k to values.joinToString(",")
            }

    return ZetaHttpResponse(
        this,
        effectiveHeaders,
        HttpStatusCode.fromValue(effectiveStatus),
    )
}

public val zetaJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

public fun isAslEncryptedResponse(path: String, contentType: String?): Boolean {
    return path.startsWith("/ASL/") &&
        contentType?.startsWith(ContentType.Application.OctetStream.toString()) == true
}
