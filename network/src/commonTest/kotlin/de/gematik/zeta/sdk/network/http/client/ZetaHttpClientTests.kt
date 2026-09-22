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

import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyType
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [zetaHttpClient].
 */
class ZetaHttpClientTests {

    private val noDependencies = HttpClientDependencies(
        revocationChecker = null,
        SystemZetaClock,
    )

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
            zetaHttpClient({ ZetaHttpClientBuilder().build(engine) }, dependencies = noDependencies)
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
        val response = client.delete(url)

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
            configure = { contentNegotiation = true },
            dependencies = noDependencies,
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
            dependencies = noDependencies,
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
            dependencies = noDependencies,
        )

        // Assert
        assertNotNull(client)
        client.close()
    }

    @Test
    fun build_returnsNonNullClient_withDefaults() {
        val client = ZetaHttpClientBuilder().build(okEngine())
        assertNotNull(client)
        client.close()
    }

    @Test
    fun isServerValidationDisabled_returnsFalse_byDefault() {
        assertFalse(ZetaHttpClientBuilder().isServerValidationDisabled)
    }

    @Test
    fun build_appliesBaseUrl_toRelativeRequests() = runTest {
        var seenHost = ""
        val engine = MockEngine { req ->
            seenHost = req.url.host
            respond("ok", HttpStatusCode.OK)
        }
        ZetaHttpClientBuilder("http://base.example.com").build(engine).get("/path")
        assertEquals("base.example.com", seenHost)
    }

    @Test
    fun timeouts_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.timeouts(connectMs = 1000, requestMs = 2000)
        assertSame(result, builder)
    }

    @Test
    fun timeouts_requestTimeout_causesTimeoutException_whenResponseSlower() = runTest {
        val engine = MockEngine { delay(200); respond("ok", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().timeouts(requestMs = 50).build(engine)
        assertFailsWith<HttpRequestTimeoutException> { client.get("/") }
        client.close()
    }

    @Test
    fun timeouts_requestTimeout_doesNotTimeout_whenResponseFaster() = runTest {
        val engine = MockEngine { delay(50); respond("ok", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().timeouts(requestMs = 500).build(engine)
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        client.close()
    }

    @Test
    fun timeouts_nullConnectMs_keepsExistingValue() = runTest {
        val engine = MockEngine { delay(200); respond("ok", HttpStatusCode.OK) }

        val client = ZetaHttpClientBuilder().timeouts(requestMs = 50).timeouts(connectMs = null).build(engine)
        assertFailsWith<HttpRequestTimeoutException> { client.get("/") }
        client.close()
    }

    @Test
    fun timeouts_nullRequestMs_keepsExistingValue() = runTest {
        val engine = MockEngine { delay(200); respond("ok", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder()
            .timeouts(requestMs = 50)
            .timeouts(requestMs = null)
            .build(engine)
        assertFailsWith<HttpRequestTimeoutException> { client.get("/") }
        client.close()
    }

    @Test
    fun retry_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.retry(maxRetries = 2)
        assertSame(result, builder)
    }

    @Test
    fun retry_retriesRequest_whenStatusCodeMatches() = runTest {
        var hits = 0
        val engine = MockEngine {
            if (hits++ < 1) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = ZetaHttpClientBuilder()
            .retry(statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = 2)
            .build(engine)
        assertEquals("ok", client.get("/").bodyAsText())
        client.close()
    }

    @Test
    fun retry_doesNotRetry_whenMaxRetriesIsZero() = runTest {
        var hits = 0
        val engine = MockEngine { hits++; respond("", HttpStatusCode.ServiceUnavailable) }
        val client = ZetaHttpClientBuilder()
            .retry(statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = 0)
            .build(engine)
        client.get("/")
        assertEquals(1, hits)
        client.close()
    }

    @Test
    fun retry_doesNotRetry_whenStatusCodeNotInSet() = runTest {
        var hits = 0
        val engine = MockEngine { hits++; respond("", HttpStatusCode.NotFound) }
        val client = ZetaHttpClientBuilder()
            .retry(statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = 3)
            .build(engine)
        client.get("/")
        assertEquals(1, hits)
        client.close()
    }

    @Test
    fun retry_retriesOnException_forIdempotentMethod_whenIdempotentTrue() = runTest {
        var hits = 0
        val engine = MockEngine {
            if (hits++ == 0) throw IOException("boom") else respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        assertEquals("ok", client.get("/").bodyAsText())
        client.close()
    }

    @Test
    fun retry_doesNotRetryOnException_forPost_whenIdempotentTrue() = runTest {
        var hits = 0
        val engine = MockEngine { hits++; throw IOException("boom") }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 2)
            .build(engine)
        runCatching { client.post("/") }
        assertEquals(1, hits)
        client.close()
    }

    @Test
    fun retry_retriesOnException_forPost_whenIdempotentFalse() = runTest {
        var hits = 0
        val engine = MockEngine {
            if (hits++ == 0) throw IOException("boom") else respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = false, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        assertEquals("ok", client.post("/").bodyAsText())
        client.close()
    }

    @Test
    fun disableServerValidation_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.disableServerValidation(true)
        assertTrue(result === builder)
    }

    @Test
    fun isServerValidationDisabled_returnsTrue_afterDisabling() {
        assertTrue(ZetaHttpClientBuilder().disableServerValidation(true).isServerValidationDisabled)
    }

    @Test
    fun isServerValidationDisabled_returnsFalse_afterReEnabling() {
        assertFalse(
            ZetaHttpClientBuilder()
                .disableServerValidation(true)
                .disableServerValidation(false)
                .isServerValidationDisabled,
        )
    }

    @Test
    fun addCaPem_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.addCaPem("-----BEGIN CERTIFICATE-----")
        assertSame(result, builder)
    }

    @Test
    fun addCaPem_accumulatesMultiplePems_onSuccessiveCalls() {
        val client = ZetaHttpClientBuilder()
            .addCaPem("pem1")
            .addCaPem("pem2")
            .addCaPem("pem3")
            .build(okEngine())
        assertNotNull(client)
        client.close()
    }

    @Test
    fun addCaPemFile_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.addCaPemFile("/path/to/cert.pem")
        assertSame(result, builder)
    }

    @Test
    fun setCaPems_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.setCaPems(listOf("pem1", "pem2"))
        assertSame(result, builder)
    }

    @Test
    fun setCaPems_replacesExistingPems_fromAddCaPem() {
        val client = ZetaHttpClientBuilder()
            .addCaPem("old-pem")
            .setCaPems(listOf("new-pem"))
            .build(okEngine())
        assertNotNull(client)
        client.close()
    }

    @Test
    fun setCaPems_withEmptyList_clearsAllPems() {
        val client = ZetaHttpClientBuilder()
            .addCaPem("some-pem")
            .setCaPems(emptyList())
            .build(okEngine())
        assertNotNull(client)
        client.close()
    }

    @Test
    fun logging_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.logging(LogLevel.INFO)
        assertSame(result, builder)
    }

    @Test
    fun logging_noneLevel_emitsNoLogs() = runTest {
        val logger = CaptureLogger()
        val engine = MockEngine { respond("resp", HttpStatusCode.OK) }
        ZetaHttpClientBuilder().logging(LogLevel.NONE, logger).build(engine).get("/test")
        assertTrue(logger.lines.isEmpty())
    }

    @Test
    fun logging_headersLevel_includesHeadersInLogs() = runTest {
        val logger = CaptureLogger()
        val engine = MockEngine { respond("resp", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().logging(LogLevel.HEADERS, logger).build(engine)
        client.get("/test") { headers.append("X-Track", "abc") }
        assertTrue(logger.lines.joinToString("\n").contains("X-Track"))
    }

    @Test
    fun logging_customLogProvider_receivesLogLines() = runTest {
        val logger = CaptureLogger()
        val engine = MockEngine { respond("resp", HttpStatusCode.OK) }
        ZetaHttpClientBuilder().logging(LogLevel.INFO, logger).build(engine).get("/test")
        assertTrue(logger.lines.isNotEmpty())
    }

    @Test
    fun proxy_returnsBuilder_forChaining() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.proxy(ProxyConfig(type = ProxyType.HTTP, host = "proxy.example.com", port = 8080))
        assertSame(result, builder)
    }

    @Test
    fun proxy_buildsClientSuccessfully_withProxyConfig() {
        val client = ZetaHttpClientBuilder()
            .proxy(ProxyConfig(type = ProxyType.HTTP, host = "proxy.example.com", port = 8080))
            .build(okEngine())
        assertNotNull(client)
        client.close()
    }

    @Test
    fun fullChain_buildsClientSuccessfully() = runTest {
        var seenHost = ""
        val engine = MockEngine { req ->
            seenHost = req.url.host
            respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder("http://chained.example.com")
            .timeouts(connectMs = 5_000, requestMs = 15_000)
            .retry(statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = 3)
            .disableServerValidation(false)
            .addCaPem("pem1")
            .logging(LogLevel.NONE)
            .build(engine)

        client.get("/hello")
        assertEquals("chained.example.com", seenHost)
        client.close()
    }

    @Test
    fun buildWithExtras_appliesExtraConfiguration() {
        var extrasApplied = false
        val client = ZetaHttpClientBuilder().build(addExtras = { extrasApplied = true })
        assertTrue(extrasApplied)
        client.close()
    }

    @Test
    fun buildWithEngine_usesInjectedEngine() = runTest {
        val engine = MockEngine { respond("engine-response", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().build(engine = engine)
        assertEquals("engine-response", client.get("/").bodyAsText())
        client.close()
    }

    @Test
    fun storedCookies_areSent_onSubsequentRequests() = runTest {
        var capturedCookieHeader: String? = null
        val mockEngine = MockEngine { request ->
            capturedCookieHeader = request.headers[HttpHeaders.Cookie]
            respond("", HttpStatusCode.OK, headersOf("Set-Cookie", "session=abc123; Path=/"))
        }

        val client = ZetaHttpClientBuilder("http://localhost").build(mockEngine)

        client.get("http://localhost/login")
        client.get("http://localhost/protected")

        assertNotNull(capturedCookieHeader)
        assertTrue(capturedCookieHeader!!.contains("session=abc123"))
    }

    @Test
    fun cookies_areStored_inClientAfterResponse() = runTest {
        val mockEngine = MockEngine {
            respond("", HttpStatusCode.OK, headersOf("Set-Cookie", "session=abc123; Path=/"))
        }
        val client = ZetaHttpClientBuilder("http://localhost").build(mockEngine)

        client.get("http://localhost/login")
        val cookies = client.useRaw { cookies("http://localhost") }

        val sessionCookie = cookies.find { it.name == "session" }
        assertNotNull(sessionCookie)
        assertEquals("abc123", sessionCookie.value)
    }

    @Test
    fun cookies_areRemoved_afterExpiryHeader() = runTest {
        var callCount = 0
        val mockEngine = MockEngine {
            callCount++
            if (callCount == 1) {
                respond("", HttpStatusCode.OK, headersOf("Set-Cookie", "session=abc123; Path=/"))
            } else {
                respond("", HttpStatusCode.OK, headersOf("Set-Cookie", "session=; Path=/; Max-Age=0"))
            }
        }
        val client = ZetaHttpClientBuilder("http://localhost").build(mockEngine)

        client.get("http://localhost/login")
        client.get("http://localhost/logout")
        val cookies = client.useRaw { cookies("http://localhost") }

        assertTrue(cookies.none { it.name == "session" && it.value.isNotEmpty() })
    }

    @Test
    fun cookies_areCleared_afterClientClose() = runTest {
        val mockEngine = MockEngine {
            respond("", HttpStatusCode.OK, headersOf("Set-Cookie", "session=abc123; Path=/"))
        }

        val client = ZetaHttpClientBuilder("http://localhost").build(mockEngine)
        client.get("http://localhost/any")
        client.close()
        val freshClient = ZetaHttpClientBuilder("http://localhost").build(mockEngine)
        val cookies = freshClient.useRaw { cookies("http://localhost") }

        assertTrue(cookies.isEmpty())
    }

    @Test
    fun cookies_areNotSharedBetweenDifferentClientInstances() = runTest {
        val mockEngine = MockEngine {
            respond("", HttpStatusCode.OK, headersOf("Set-Cookie", "session=abc123; Path=/"))
        }

        val client1 = ZetaHttpClientBuilder("http://localhost").build(mockEngine)
        val client2 = ZetaHttpClientBuilder("http://localhost").build(mockEngine)
        client1.get("http://localhost/any")
        val cookiesClient2 = client2.useRaw { cookies("http://localhost") }

        assertTrue(cookiesClient2.isEmpty())
    }

    @Test
    fun zetaHttpClient_installsContentNegotiation_whenEnabled() {
        // Arrange & Act
        val client = zetaHttpClient(
            configure = { contentNegotiation = true },
            dependencies = noDependencies,
        )

        // Assert
        val hasPlugin = client.useRaw { pluginOrNull(ContentNegotiation) != null }
        assertTrue(hasPlugin)
        client.close()
    }

    @Test
    fun zetaHttpClient_installsContentNegotiation_byDefault() {
        // Arrange & Act
        val client = zetaHttpClient(
            configure = { contentNegotiation = true },
            dependencies = noDependencies,
        )

        // Assert
        val hasPlugin = client.useRaw { pluginOrNull(ContentNegotiation) != null }
        assertTrue(hasPlugin)
        client.close()
    }

    /** Minimal logger that captures log lines for assertions. */
    private class CaptureLogger : Logger {
        val lines = mutableListOf<String>()
        override fun log(message: String) { lines += message }
    }

    private fun okEngine(body: String = "ok") =
        MockEngine { respond(body, HttpStatusCode.OK) }
}
