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

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.content.ByteArrayContent
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.charset
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.text.ifEmpty

private const val CRLF = "\r\n"
private const val HTTP_VERSION = "HTTP/1.1"

public class InnerHttpCodec {
    /**
     * Encode a Ktor HttpRequestBuilder into a raw HTTP/1.1 request:
     */
    public fun encodeRequest(builder: HttpRequestBuilder): ByteArray {
        val method = builder.method.value
        val requestTarget = buildRequestTarget(builder)
        val (bodyBytes, bodyContentType) = extractBody(builder)

        val headerMap: Map<String, List<String>> =
            buildHeaderMap(builder)
                .withHostHeaderIfMissing(builder)
                .withBodyHeaders(bodyBytes, bodyContentType)

        val headBytes = buildHeadBytes(method, requestTarget, headerMap)

        return headBytes + bodyBytes
    }

    /**
     * Decode a raw HTTP/1.1 response into [InnerHttpResponse]
     */
    public fun decodeResponse(bytes: ByteArray): InnerHttpResponse {
        val (headerBytes, bodyBytes) = splitHeaderAndBody(bytes)

        val headerText = headerBytes.decodeToString()
        val lines = headerText.split(CRLF)

        require(lines.isNotEmpty() && lines.first().isNotBlank()) {
            "Invalid HTTP message: missing or empty status line"
        }

        val (status, reason) = parseStatusLine(lines.first())
        val headers = parseHeaders(lines.drop(1)).toMutableMap()

        // Always reflect the actual body size
        headers[HttpHeaders.ContentLength] = bodyBytes.size.toString()

        return InnerHttpResponse(
            status = status,
            reason = reason,
            headers = headers,
            body = bodyBytes,
        )
    }

    private data class BodyInfo(
        val bytes: ByteArray,
        val contentType: ContentType?,
    )

    private fun extractBody(builder: HttpRequestBuilder): BodyInfo {
        val body = builder.body

        return when (body) {
            is ByteArrayContent -> BodyInfo(body.bytes(), body.contentType)

            is TextContent -> BodyInfo(body.text.toByteArray(body.contentType.charset() ?: Charsets.UTF_8), body.contentType)

            is OutgoingContent.NoContent -> BodyInfo(ByteArray(0), null)

            is ByteArray -> BodyInfo(
                body,
                builder.headers[HttpHeaders.ContentType]?.let {
                    ContentType.parse(it)
                },
            )

            else ->
                error("Provide a canonical serializer for the inner HTTP request per A_26927.")
        }
    }

    private fun buildRequestTarget(builder: HttpRequestBuilder): String {
        val path = builder.url.encodedPath.ifEmpty { "/" }
        val query = builder.url.parameters

        return if (query.isEmpty()) path else "$path?$query"
    }

    private fun buildHeaderMap(builder: HttpRequestBuilder): LinkedHashMap<String, MutableList<String>> {
        val headerMap = linkedMapOf<String, MutableList<String>>()
        for ((name, values) in builder.headers.entries()) {
            headerMap.getOrPut(name) { mutableListOf() }.addAll(values)
        }
        return headerMap
    }

    private fun MutableMap<String, MutableList<String>>.withHostHeaderIfMissing(
        builder: HttpRequestBuilder,
    ): MutableMap<String, MutableList<String>> {
        val hasHost = keys.any { it.equals(HttpHeaders.Host, ignoreCase = true) }
        if (hasHost) return this

        val hostValue = buildString {
            append(builder.url.host)
            val port = builder.url.port
            val defaultPort = when (builder.url.protocol) {
                URLProtocol.HTTPS -> 443
                URLProtocol.HTTP -> 80
                else -> -1
            }
            if (port != 0 && port != defaultPort && port != -1) {
                append(':')
                append(port)
            }
        }

        getOrPut(HttpHeaders.Host) { mutableListOf() }.add(hostValue)
        return this
    }

    private fun MutableMap<String, MutableList<String>>.withBodyHeaders(
        bodyBytes: ByteArray,
        bodyContentType: ContentType?,
    ): MutableMap<String, MutableList<String>> {
        if (bodyBytes.isEmpty()) return this

        val keysToRemove = keys.filter { it.equals(HttpHeaders.ContentLength, ignoreCase = true) }
        keysToRemove.forEach(::remove)

        this[HttpHeaders.ContentLength] = mutableListOf(bodyBytes.size.toString())

        val ct = bodyContentType?.toString()
            ?: ContentType.Application.OctetStream.toString()
        this[HttpHeaders.ContentType] = mutableListOf(ct)

        return this
    }

    private fun buildHeadBytes(
        method: String,
        requestTarget: String,
        headerMap: Map<String, List<String>>,
    ): ByteArray {
        val headerLines = buildString {
            headerMap.entries
                .sortedBy { it.key.lowercase() }
                .forEach { (name, values) ->
                    for (v in values) {
                        append(name)
                        append(": ")
                        append(v)
                        append(CRLF)
                    }
                }
        }

        val requestLine = "$method $requestTarget $HTTP_VERSION$CRLF"

        val headString = buildString {
            append(requestLine)
            append(headerLines)
            append(CRLF)
        }

        return headString.encodeToByteArray()
    }

    private fun splitHeaderAndBody(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val delimiter = (CRLF + CRLF).encodeToByteArray()
        val headerEndIndex = bytes.indexOfSubsequence(delimiter)
        require(headerEndIndex >= 0) {
            "Invalid HTTP message: missing header terminator (CRLF CRLF)"
        }

        val headerBytes = bytes.copyOfRange(0, headerEndIndex)
        val bodyBytes = bytes.copyOfRange(headerEndIndex + delimiter.size, bytes.size)

        return headerBytes to bodyBytes
    }

    private fun parseStatusLine(statusLine: String): Pair<Int, String> {
        val parts = statusLine.split(" ", limit = 3)
        require(parts.size >= 2) { "Invalid status line: $statusLine" }

        val status = parts[1].toIntOrNull()
            ?: error("Invalid status code in line: $statusLine")

        val reason = if (parts.size >= 3) parts[2] else ""

        return status to reason
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headersMap = LinkedHashMap<String, String>()
        for (line in lines) {
            if (line.isBlank()) continue

            val idx = line.indexOf(':')
            if (idx <= 0) continue

            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            headersMap[name] = value
        }
        return headersMap
    }

    private fun ByteArray.indexOfSubsequence(sub: ByteArray): Int {
        if (sub.isEmpty()) return 0
        outer@ for (i in 0..(this.size - sub.size)) {
            for (j in sub.indices) {
                if (this[i + j] != sub[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

public data class InnerHttpResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)
