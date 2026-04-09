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

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [zetaHttpClient].
 */
class ZetaHttpClientTests {
    @Test
    fun testBaseUrlAppliedToRelativeRequests() = runTest {
        // Arrange
        val expectedHost = "dummy.example.org"
        var seenHost = ""

        val engine = MockEngine { req ->
            seenHost = req.url.host
            respond("ok", HttpStatusCode.OK)
        }

        // Act
        val client = ZetaHttpClientBuilder("http://$expectedHost")
            .build(engine)

        client.get("/hellozeta")

        // Assert
        assertEquals(expectedHost, seenHost)
    }

    @Test
    fun testBaseUrlWithPathPrefixesRelativeRequests() = runTest {
        // Arrange
        var seenPath = ""
        val expectedEndpoint = "/testfachdienst/hellozeta"

        val engine = MockEngine { req ->
            seenPath = req.url.encodedPath
            respond("ok", HttpStatusCode.OK)
        }

        // Act
        val client =
            ZetaHttpClientBuilder("http://dummy.example.org")
                .build(engine)

        client.get(expectedEndpoint)

        // Assert
        assertEquals(expectedEndpoint, seenPath)
    }

    @Test
    fun testTimeoutsShortRequestTimesOut() = runTest {
        // Arrange
        val engine = MockEngine {
            delay(150)
            respond("ok", HttpStatusCode.OK)
        }

        // Act
        val client =
            ZetaHttpClientBuilder()
                .timeouts(requestMs = 50)
                .build(engine)

        // Assert
        assertFailsWith<HttpRequestTimeoutException> { client.get("/") }
    }

    @Test
    fun testTimeoutsLongerRequestDoesNotTimeout() = runTest {
        // Arrange
        val engine = MockEngine {
            delay(50)
            respond("ok", HttpStatusCode.OK)
        }
        val client =
            ZetaHttpClientBuilder()
                .timeouts(requestMs = 150)
                .build(engine)

        // Act
        val res = client.get("/slow-ok")

        // Assert
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun testRetryGetSucceedsWithinMaxRetries() = runTest {
        // Arrange
        var retryHits = 0
        val maxRetries = 2
        val engine = MockEngine {
            if (retryHits++ < 2) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }

        // Act
        val client =
            ZetaHttpClientBuilder()
                .retry(
                    onlyIdempotent = true,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                    maxRetries = maxRetries,
                )
                .build(engine)

        val body = client
            .get("/")
            .bodyAsText()
        // Assert
        assertEquals("ok", body)
    }

    @Test
    fun testRetryExceededStillFails() = runTest {
        // Arrange
        val maxRetries = 2
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }

        // Act
        val client =
            ZetaHttpClientBuilder()
                .retry(
                    onlyIdempotent = true,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                    maxRetries = maxRetries,
                )
                .build(engine)

        val res = client.get("/")

        // Assert
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }

    @Test
    fun testRetrySkipsPostWhenIdempotentOnlyTrue() = runTest {
        // Arrange
        var retryHits = 0
        val engine = MockEngine {
            retryHits++
            respond("", HttpStatusCode.TooManyRequests)
        }

        // Act
        val client =
            ZetaHttpClientBuilder()
                .retry(
                    onlyIdempotent = true,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                )
                .build(engine)

        runCatching { client.post("/") { setBody("x") } }

        // Assert
        assertEquals(1, retryHits)
    }

    @Test
    fun testRetrySkipsPostWhenIdempotentOnlyFalse() = runTest {
        // Arrange
        var retryHits = 0

        val engine = MockEngine {
            retryHits++
            if (retryHits++ == 0) {
                respond("", HttpStatusCode.TooManyRequests)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }

        // Act
        val client =
            ZetaHttpClientBuilder()
                .retry(
                    onlyIdempotent = false,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                )
                .build(engine)

        runCatching { client.post("/") { setBody("x") } }

        // Assert
        assertEquals(2, retryHits)
    }

    @Test
    fun testRetryPutConsideredIdempotentWhenIdempotentOnlyTrue() = runTest {
        // Arrange
        var retryHits = 0
        val maxRetries = 1
        val engine = MockEngine {
            if (retryHits++ == 0) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }

        // Act
        val client =
            ZetaHttpClientBuilder()
                .retry(
                    onlyIdempotent = false,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                    maxRetries = maxRetries,
                )
                .build(engine)

        client.put("/resource")

        // Assert
        assertEquals(2, retryHits)
    }

    @Test
    fun testRetryStatusNotInSetDoesNotRetry() = runTest {
        // Arrange
        var retyHits = 0
        val maxRetries = 3
        val engine = MockEngine {
            retyHits++
            respond("", HttpStatusCode.NotFound)
        }

        // Act
        val client =
            ZetaHttpClientBuilder()
                .retry(
                    onlyIdempotent = false,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                    maxRetries = maxRetries,
                )
                .build(engine)

        client.get("/missing")

        // Assert
        assertEquals(1, retyHits)
    }

    @Test
    fun testEngineFailurePropagates() = runTest {
        // Arrange
        val engine = MockEngine { error("error") }
        // Act + Assert
        assertFailsWith<Throwable> {
            zetaHttpClient({ ZetaHttpClientBuilder().build(engine) })
                .get("/")
        }
    }

    @Test
    fun testLogLevelNoneEmitsNoLogs() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("resp", HttpStatusCode.OK)
        }

        // Act
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .logging(LogLevel.NONE, logProvider = logger)
                .build(engine)

        client.get("/none")

        // Assert
        assertTrue(logger.lines.isEmpty())
    }

    @Test
    fun testLogLevelInfoOmitsHeadersAndBodies() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        // Act
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .logging(LogLevel.INFO, logProvider = logger)
                .build(engine)
        client.post("/info") {
            header("X-Token", "abc123")
            setBody("""{"p":"payload-test"}""")
        }

        // Assert
        assertTrue(logger.lines.joinToString("\n").let { it.isNotEmpty() && "X-Token" !in it && "payload-abc" !in it })
    }

    @Test
    fun testLogLevelHeadersIncludesHeadersNotBodies() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .logging(LogLevel.HEADERS, logProvider = logger)
                .build(engine)

        // Act
        client.post("/header") {
            header("X-Token", "abc123")
            setBody("""{"p":"payload-test"}""")
        }

        // Assert
        assertTrue(logger.lines.joinToString("\n").let { it.isNotEmpty() && "X-Token" in it && "payload-abc" !in it })
    }

    @Test
    fun testLogLevelBodyIncludesHeadersAndBodies() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("""{"resp":"r-123"}""", HttpStatusCode.OK)
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .logging(LogLevel.ALL, logProvider = logger)
                .build(engine)

        // Act
        client.post("/body") {
            header("X-Token", "abc123")
            setBody("""{"p":"payload-test"}""")
            contentType(ContentType.Application.Json)
        }

        // Assert
        assertTrue(
            logger.lines.joinToString("\n").let {
                it.isNotEmpty() &&
                    "X-Token" in it &&
                    "payload-test" in it &&
                    "r-123" in it
            },
        )
    }

    @Test
    fun testRetryOnExceptionGetRetriedWhenIdempotentTrue() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) {
                throw IOException("boom")
            } else {
                respond(
                    "ok",
                    HttpStatusCode.OK,
                )
            }
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.get("/").bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    @Test
    fun testRetryOnExceptionPutRetriedWhenIdempotentTrue() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) {
                throw IOException("boom")
            } else {
                respond(
                    "ok",
                    HttpStatusCode.OK,
                )
            }
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.put("/resource") { setBody("x") }.bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    @Test
    fun testRetryOnExceptionPostNotRetriedWhenIdempotentTrue() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException("boom") } // always fail
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        runCatching { client.post("/") { setBody("x") } } // call fails; we just observe attempts

        // Assert
        assertEquals(1, hits[0]) // no retry took place
    }

    @Test
    fun testRetryOnExceptionPatchNotRetriedWhenIdempotentTrue() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException("boom") } // always fail
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        runCatching { client.patch("/") { setBody("y") } }
        // Assert
        assertEquals(1, hits[0])
    }

    @Test
    fun testRetryOnExceptionPostRetriedWhenIdempotentFalse() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) {
                throw IOException("boom")
            } else {
                respond(
                    "ok",
                    HttpStatusCode.OK,
                )
            }
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .retry(onlyIdempotent = false, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.post("/") { setBody("x") }.bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    @Test
    fun testRetryOnExceptionNoRetryWhenMaxRetriesZero() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException("boom") } // always fail
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 0)
                .build(engine)

        // Act
        runCatching { client.get("/") }

        // Assert
        assertEquals(1, hits[0])
    }

    @Test
    fun testRetryOnExceptionPatchRetriedWhenIdempotentFalse() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) {
                throw IOException("boom")
            } else {
                respond(
                    "ok",
                    HttpStatusCode.OK,
                )
            }
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder()
                .retry(onlyIdempotent = false, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.patch("/") { setBody("y") }.bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    @Test
    fun hostOf_extractsHost_fullUrl() {
        // Arrange & Act
        val host = hostOf("https://api.example.com/path")

        // Assert
        assertEquals("api.example.com", host)
    }

    @Test
    fun hostOf_extractsHost_urlWithoutProtocol() {
        // Arrange & Act
        val host = hostOf("api.example.com")

        // Assert
        assertEquals("api.example.com", host)
    }

    @Test
    fun hostOf_extractsHost_urlWithPort() {
        // Arrange & Act
        val host = hostOf("https://api.example.com:8080/path")

        // Assert
        assertEquals("api.example.com", host)
    }

    @Test
    fun hostOf_convertsToLowercase_uppercaseHost() {
        // Arrange & Act
        val host = hostOf("https://API.EXAMPLE.COM")

        // Assert
        assertEquals("api.example.com", host)
    }

    @Test
    fun hostOf_removesTrailingDot_hostWithDot() {
        // Arrange & Act
        val host = hostOf("https://api.example.com.")

        // Assert
        assertEquals("api.example.com", host)
    }

    @Test
    fun hostOf_trimsWhitespace_hostWithSpaces() {
        // Arrange & Act
        val host = hostOf("  https://api.example.com  ")

        // Assert
        assertEquals("api.example.com", host)
    }

    @Test
    fun hostOf_handlesDoubleSlashPrefix_urlWithoutProtocol() {
        // Arrange & Act
        val host = hostOf("//api.example.com/path")

        // Assert
        assertEquals("api.example.com", host)
    }

    @Test
    fun hostOf_extractsHost_subdomain() {
        // Arrange & Act
        val host = hostOf("https://dev.api.example.com")

        // Assert
        assertEquals("dev.api.example.com", host)
    }

    @Test
    fun delete_performsDeleteRequest_urlString() = runTest {
        // Arrange
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.NoContent,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))

        // Act
        val response = client.delete("/test")

        // Assert
        assertEquals(204, response.status.value)
    }

    @Test
    fun delete_performsDeleteRequest_urlObject() = runTest {
        // Arrange
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))
        val url = Url("https://example.com/test")

        // Act
        val response = client.delete<Unit>(url)

        // Assert
        assertNotNull(response)
    }

    @Test
    fun request_performsRequest_configBlockOnly() = runTest {
        // Arrange
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))

        // Act
        val response = client.request {
            method = HttpMethod.Get
            url("/test")
        }

        // Assert
        assertNotNull(response)
    }

    @Test
    fun request_performsRequest_urlString() = runTest {
        // Arrange
        val mockEngine = MockEngine { request ->
            assertEquals("/test", request.url.encodedPath)
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))

        // Act
        val response = client.request("/test") {
            method = HttpMethod.Get
        }

        // Assert
        assertNotNull(response)
    }

    @Test
    fun request_performsRequest_urlObject() = runTest {
        // Arrange
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))
        val url = Url("https://example.com/test")

        // Act
        val response = client.request(url) {
            method = HttpMethod.Get
        }

        // Assert
        assertNotNull(response)
    }

    @Test
    fun submitForm_sendsFormData_urlString() = runTest {
        // Arrange
        var isFormData = false
        val mockEngine = MockEngine { request ->
            isFormData = request.body is FormDataContent
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))
        val params = Parameters.build {
            append("key", "value")
        }

        // Act
        val response = client.submitForm("/test", params)

        // Assert
        assertTrue(isFormData)
        assertNotNull(response)
    }

    @Test
    fun submitForm_encodesInQuery_whenFlagSet() = runTest {
        // Arrange
        var queryFound = false
        val mockEngine = MockEngine { request ->
            queryFound = request.url.parameters.contains("key")
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))
        val params = Parameters.build {
            append("key", "value")
        }

        // Act
        client.submitForm("/test", params, encodeInQuery = true)

        // Assert
        assertTrue(queryFound)
    }

    @Test
    fun submitForm_appliesConfigBlock_customHeaders() = runTest {
        // Arrange
        var headerFound = false
        val mockEngine = MockEngine { request ->
            headerFound = request.headers.contains("X-Custom", "value")
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK,
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))
        val params = Parameters.build {
            append("key", "value")
        }

        // Act
        client.submitForm("/test", params) {
            headers.append("X-Custom", "value")
        }

        // Assert
        assertTrue(headerFound)
    }

    @Test
    fun close_closesUnderlyingClient_doesNotThrow() {
        // Arrange
        val mockEngine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))

        // Act & Assert
        client.close()
    }

    @Test
    fun useRaw_providesAccessToDelegate_returnsValue() {
        // Arrange
        val mockEngine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))

        // Act
        val result = client.useRaw {
            "test value"
        }

        // Assert
        assertEquals("test value", result)
    }

    @Test
    fun get_returnsZetaHttpResponse_withCorrectStatus() = runTest {
        // Arrange
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("test body"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("text/plain")),
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))

        // Act
        val response = client.get("/test")

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("test body", response.bodyAsText())
    }

    @Test
    fun post_returnsZetaHttpResponse_withHeaders() = runTest {
        // Arrange
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.Created,
                headers = headersOf(
                    "X-Custom-Header" to listOf("custom-value"),
                    "Content-Type" to listOf("application/json"),
                ),
            )
        }
        val client = ZetaHttpClient(io.ktor.client.HttpClient(mockEngine))

        // Act
        val response = client.post("/test")

        // Assert
        assertEquals("custom-value", response.headers["X-Custom-Header"])
        assertEquals("application/json", response.headers["Content-Type"])
    }

    @Test
    fun zetaHttpClient_installsContentNegotiation_always() {
        // Arrange & Act
        val client = zetaHttpClient(
            configure = {},
        )

        // Assert
        val hasPlugin = client.useRaw {
            pluginOrNull(ContentNegotiation) != null
        }
        assertTrue(hasPlugin)
        client.close()
    }

    @Test
    fun zetaHttpClient_appliesExtras_whenProvided() {
        // Arrange
        var extrasApplied = false

        // Act
        val client = zetaHttpClient(
            configure = {},
            addExtras = {
                extrasApplied = true
            },
        )

        // Assert
        assertTrue(extrasApplied)
        client.close()
    }

    @Test
    fun zetaHttpClient_usesInjectedEngine_whenProvided() {
        // Arrange
        val mockEngine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }

        // Act
        val client = zetaHttpClient(
            configure = {
                engineFactory = { mockEngine }
            },
        )

        // Assert
        assertNotNull(client)
        client.close()
    }

    /** Minimal logger that captures log lines for assertions. */
    private class CaptureLogger : Logger {
        val lines = mutableListOf<String>()
        override fun log(message: String) { lines += message }
    }
}
