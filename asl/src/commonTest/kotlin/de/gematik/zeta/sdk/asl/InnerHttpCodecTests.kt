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
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InnerHttpCodecTest {
    private val codec = InnerHttpCodecImpl()
    private val CRLF = "\r\n"

    @Test
    fun encodeRequest_containsRequestLine_getRequest() {
        // Arrange
        val request = buildRequest(method = HttpMethod.Get)

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.startsWith("GET /resource HTTP/1.1$CRLF"))
    }

    @Test
    fun encodeRequest_containsRequestLine_postRequest() {
        // Arrange
        val request = buildRequest(method = HttpMethod.Post)

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.startsWith("POST /resource HTTP/1.1$CRLF"))
    }

    @Test
    fun encodeRequest_usesRootPath_emptyPath() {
        // Arrange
        val request = buildRequest(url = "https://api.example.com")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.startsWith("GET / HTTP/1.1$CRLF"))
    }

    @Test
    fun encodeRequest_includesQueryString_queryPresent() {
        // Arrange
        val request = buildRequest(url = "https://api.example.com/resource?foo=bar")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("/resource?foo=bar"))
    }

    @Test
    fun encodeRequest_addsHostHeader_hostHeaderAbsent() {
        // Arrange
        val request = buildRequest(url = "https://api.example.com/resource")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("Host: api.example.com$CRLF"))
    }

    @Test
    fun encodeRequest_doesNotDuplicateHostHeader_hostHeaderPresent() {
        // Arrange
        val request = buildRequest {
            header(HttpHeaders.Host, "custom.host.com")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("Host: custom.host.com$CRLF"))
        assertFalse(result.contains("Host: api.example.com"))
    }

    @Test
    fun encodeRequest_omitsPort_defaultHttpsPort() {
        // Arrange
        val request = buildRequest(url = "https://api.example.com:443/resource")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("Host: api.example.com$CRLF"))
    }

    @Test
    fun encodeRequest_appendsPort_nonDefaultHttpsPort() {
        // Arrange
        val request = buildRequest(url = "https://api.example.com:8443/resource")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("Host: api.example.com:8443$CRLF"))
    }

    @Test
    fun encodeRequest_omitsPort_defaultHttpPort() {
        // Arrange
        val request = buildRequest(url = "http://api.example.com:80/resource")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("Host: api.example.com$CRLF"))
    }

    @Test
    fun encodeRequest_appendsPort_nonDefaultHttpPort() {
        // Arrange
        val request = buildRequest(url = "http://api.example.com:8080/resource")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("Host: api.example.com:8080$CRLF"))
    }

    @Test
    fun encodeRequest_encodesBody_byteArrayContent() {
        // Arrange
        val bodyBytes = "hello".encodeToByteArray()
        val request = buildRequest(method = HttpMethod.Post) {
            setBody(ByteArrayContent(bodyBytes, ContentType.Text.Plain))
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.endsWith("hello"))
        assertTrue(result.contains("Content-Length: 5$CRLF"))
        assertTrue(result.contains("Content-Type: text/plain"))
    }

    @Test
    fun encodeRequest_encodesBody_textContentWithCharset() {
        // Arrange
        val request = buildRequest(method = HttpMethod.Post) {
            setBody(TextContent("hello", ContentType.Text.Plain.withCharset(Charsets.UTF_8)))
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.endsWith("hello"))
        assertTrue(result.contains("Content-Length: 5$CRLF"))
    }

    @Test
    fun encodeRequest_encodesBody_textContentWithoutCharset() {
        // Arrange
        val request = buildRequest(method = HttpMethod.Post) {
            setBody(TextContent("hello", ContentType.Application.OctetStream))
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.endsWith("hello"))
        assertTrue(result.contains("Content-Length: 5$CRLF"))
    }

    @Test
    fun encodeRequest_omitsContentLength_noContent() {
        // Arrange
        val request = buildRequest(method = HttpMethod.Get)

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("Content-Length"))
    }

    @Test
    fun encodeRequest_encodesBody_rawByteArrayWithoutContentTypeHeader() {
        // Arrange
        val bodyBytes = "raw".encodeToByteArray()
        val request = buildRequest(method = HttpMethod.Post) {
            setBody(bodyBytes)
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.endsWith("raw"))
        assertTrue(result.contains("Content-Type: application/octet-stream$CRLF"))
        assertTrue(result.contains("Content-Length: 3$CRLF"))
    }

    @Test
    fun encodeRequest_encodesBody_rawByteArrayWithContentTypeHeader() {
        // Arrange
        val bodyBytes = "raw".encodeToByteArray()
        val request = buildRequest(method = HttpMethod.Post) {
            header(HttpHeaders.ContentType, "application/json")
            setBody(bodyBytes)
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.endsWith("raw"))
        assertTrue(result.contains("Content-Type: application/json$CRLF"))
    }

    @Test
    fun encodeRequest_throwsIllegalStateException_unsupportedBodyType() {
        // Arrange
        val request = buildRequest(method = HttpMethod.Post) {
            setBody("plain string")
        }

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            codec.encodeRequest(request)
        }
    }

    @Test
    fun encodeRequest_replacesExistingContentLength_bodyPresent() {
        // Arrange
        val bodyBytes = "hello".encodeToByteArray()
        val request = buildRequest(method = HttpMethod.Post) {
            header(HttpHeaders.ContentLength, "999")
            setBody(ByteArrayContent(bodyBytes, ContentType.Text.Plain))
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("Content-Length: 5$CRLF"))
        assertFalse(result.contains("Content-Length: 999"))
    }

    @Test
    fun decodeResponse_parsesStatusAndBody_validResponse() {
        // Arrange
        val raw = buildRawResponse(status = 200, reason = "OK", body = "response body")

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals(200, result.status)
        assertEquals("OK", result.reason)
        assertEquals("response body", result.body.decodeToString())
    }

    @Test
    fun decodeResponse_parsesEmptyBody_validResponseNoBody() {
        // Arrange
        val raw = buildRawResponse(status = 204, reason = "No Content", body = "")

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals(204, result.status)
        assertEquals(0, result.body.size)
    }

    @Test
    fun decodeResponse_setsContentLength_bodyPresent() {
        // Arrange
        val raw = buildRawResponse(body = "12345")

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals("5", result.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun decodeResponse_parsesHeaders_validResponse() {
        // Arrange
        val headers = mapOf("X-Custom" to "value1", "X-Other" to "value2")
        val raw = buildRawResponse(headers = headers)

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals("value1", result.headers["X-Custom"])
        assertEquals("value2", result.headers["X-Other"])
    }

    @Test
    fun decodeResponse_parsesReason_reasonPresent() {
        // Arrange
        val raw = buildRawResponse(status = 404, reason = "Not Found")

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals("Not Found", result.reason)
    }

    @Test
    fun decodeResponse_parsesEmptyReason_reasonAbsent() {
        // Arrange
        val raw = "HTTP/1.1 200$CRLF$CRLF".encodeToByteArray()

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals(200, result.status)
        assertEquals("", result.reason)
    }

    @Test
    fun decodeResponse_throwsIllegalArgumentException_noHeaderTerminator() {
        // Arrange
        val raw = "HTTP/1.1 200 OK${CRLF}no-terminator".encodeToByteArray()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            codec.decodeResponse(raw)
        }
    }

    @Test
    fun decodeResponse_throwsIllegalArgumentException_emptyStatusLine() {
        // Arrange
        val raw = "$CRLF$CRLF".encodeToByteArray()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            codec.decodeResponse(raw)
        }
    }

    @Test
    fun decodeResponse_throwsIllegalArgumentException_invalidStatusLineTooFewParts() {
        // Arrange
        val raw = "HTTP/1.1$CRLF$CRLF".encodeToByteArray()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            codec.decodeResponse(raw)
        }
    }

    @Test
    fun decodeResponse_throwsIllegalStateException_nonIntStatusCode() {
        // Arrange
        val raw = "HTTP/1.1 abc OK$CRLF$CRLF".encodeToByteArray()

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            codec.decodeResponse(raw)
        }
    }

    @Test
    fun decodeResponse_skipsBlankHeaderLines() {
        // Arrange
        val raw = "HTTP/1.1 200 OK$CRLF$CRLF$CRLF X-Valid: yes$CRLF$CRLF".encodeToByteArray()

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals(200, result.status)
    }

    @Test
    fun decodeResponse_skipsHeaderLineWithoutColon() {
        // Arrange
        val raw = "HTTP/1.1 200 OK${CRLF}InvalidHeaderNoColon${CRLF}X-Valid: yes$CRLF$CRLF".encodeToByteArray()

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals("yes", result.headers["X-Valid"])
        assertFalse(result.headers.containsKey("InvalidHeaderNoColon"))
    }

    @Test
    fun decodeResponse_skipsHeaderLineWithColonAtZero() {
        // Arrange
        val raw = "HTTP/1.1 200 OK$CRLF: valueWithNoName${CRLF}X-Valid: yes$CRLF$CRLF".encodeToByteArray()

        // Act
        val result = codec.decodeResponse(raw)

        // Assert
        assertEquals("yes", result.headers["X-Valid"])
        assertFalse(result.headers.containsKey(""))
    }

    @Test
    fun encodeRequest_includesQueryString_multipleParameters() {
        // Arrange
        val request = buildRequest(url = "https://api.example.com/resource?foo=bar&baz=qux")

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(
            result.contains("GET /resource?foo=bar&baz=qux HTTP/1.1") ||
                result.contains("GET /resource?baz=qux&foo=bar HTTP/1.1"),
        )
    }

    @Test
    fun encodeRequest_includesQueryString_parameterWithMultipleValues() {
        // Arrange
        val request = HttpRequestBuilder().apply {
            url.takeFrom("https://api.example.com/resource")
            url.parameters.append("key", "value1")
            url.parameters.append("key", "value2")
            method = HttpMethod.Get
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(
            result.contains("key=value1&key=value2") ||
                result.contains("key=value2&key=value1"),
        )
    }

    @Test
    fun encodeRequest_encodesSpecialCharacters_inQueryString() {
        // Arrange
        val request = HttpRequestBuilder().apply {
            url.takeFrom("https://api.example.com/resource")
            url.parameters.append("query", "hello world")
            method = HttpMethod.Get
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(
            result.contains("query=hello+world") ||
                result.contains("query=hello%20world"),
        )
    }

    @Test
    fun encodeRequest_includesCustomHeaders_preservesValues() {
        // Arrange
        val request = buildRequest {
            headers.append("X-Custom-Header", "custom-value")
            headers.append("Authorization", "Bearer token123")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertTrue(result.contains("X-Custom-Header: custom-value$CRLF"))
        assertTrue(result.contains("Authorization: Bearer token123$CRLF"))
    }

    @Test
    fun encodeRequest_removesForwardedHeaders_xForwardedProto() {
        // Arrange
        val request = buildRequest {
            headers.append("X-Forwarded-Proto", "https")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("X-Forwarded-Proto"))
    }

    @Test
    fun encodeRequest_removesForwardedHeaders_xForwardedHost() {
        // Arrange
        val request = buildRequest {
            headers.append("X-Forwarded-Host", "original.example.com")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("X-Forwarded-Host"))
    }

    @Test
    fun encodeRequest_removesForwardedHeaders_xForwardedFor() {
        // Arrange
        val request = buildRequest {
            headers.append("X-Forwarded-For", "192.168.1.1")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("X-Forwarded-For"))
    }

    @Test
    fun encodeRequest_removesForwardedHeaders_xForwardedPort() {
        // Arrange
        val request = buildRequest {
            headers.append("X-Forwarded-Port", "8080")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("X-Forwarded-Port"))
    }

    @Test
    fun encodeRequest_removesForwardedHeaders_forwardedHeader() {
        // Arrange
        val request = buildRequest {
            headers.append("Forwarded", "for=192.168.1.1;host=example.com;proto=https")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("Forwarded:"))
    }

    @Test
    fun encodeRequest_removesAllForwardedHeaders_multiplePresent() {
        // Arrange
        val request = buildRequest {
            headers.append("X-Forwarded-Proto", "https")
            headers.append("X-Forwarded-Host", "original.example.com")
            headers.append("X-Forwarded-For", "192.168.1.1")
            headers.append("X-Forwarded-Port", "8080")
            headers.append("Forwarded", "for=192.168.1.1")
            headers.append("X-Custom-Header", "should-remain")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("X-Forwarded-Proto"))
        assertFalse(result.contains("X-Forwarded-Host"))
        assertFalse(result.contains("X-Forwarded-For"))
        assertFalse(result.contains("X-Forwarded-Port"))
        assertFalse(result.contains("Forwarded:"))
        assertTrue(result.contains("X-Custom-Header: should-remain$CRLF"))
    }

    @Test
    fun encodeRequest_preservesOtherHeaders_afterRemovingForwarded() {
        // Arrange
        val request = buildRequest {
            headers.append("X-Forwarded-Proto", "https")
            headers.append("Authorization", "Bearer token123")
            headers.append("Content-Type", "application/json")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.contains("X-Forwarded-Proto"))
        assertTrue(result.contains("Authorization: Bearer token123$CRLF"))
        assertTrue(result.contains("Content-Type: application/json$CRLF"))
    }

    @Test
    fun encodeRequest_handlesForwardedHeadersCaseInsensitive() {
        // Arrange
        val request = buildRequest {
            headers.append("x-forwarded-proto", "https")
            headers.append("X-FORWARDED-HOST", "example.com")
            headers.append("forwarded", "for=192.168.1.1")
        }

        // Act
        val result = codec.encodeRequest(request).decodeToString()

        // Assert
        assertFalse(result.lowercase().contains("x-forwarded-proto"))
        assertFalse(result.lowercase().contains("x-forwarded-host"))
        assertFalse(result.lowercase().contains("forwarded:"))
    }

    private fun buildRequest(
        url: String = "https://api.example.com/resource",
        method: HttpMethod = HttpMethod.Get,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpRequestBuilder =
        HttpRequestBuilder().apply {
            this.url.takeFrom(url)
            this.method = method
            configure()
        }

    private fun buildRawResponse(
        status: Int = 200,
        reason: String = "OK",
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): ByteArray {
        val headerLines = headers.entries.joinToString(CRLF) { "${it.key}: ${it.value}" }
        val raw = buildString {
            append("HTTP/1.1 $status $reason$CRLF")
            if (headerLines.isNotEmpty()) {
                append("$headerLines$CRLF")
            }
            append(CRLF)
            append(body)
        }
        return raw.encodeToByteArray()
    }
}

private fun assertFalse(condition: Boolean) {
    assertTrue(!condition)
}
