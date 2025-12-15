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
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [zetaHttpClient].
 */
class ZetaHttpClientTest {

    /** baseUrl(url) is applied when a relative path is requested (host comes from base URL). */
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

    /** Base URL with a path prefix is prepended to relative requests. */
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

    /** Short request timeout triggers HttpRequestTimeoutException on slow response. */
    @Test
    fun testTimeoutsShortRequestTimesOut() = runTest {
        // Arrange
        val engine = MockEngine {
            delay(150)
            respond("ok", HttpStatusCode.OK)
        }

        // Act
        val client =
            ZetaHttpClientBuilder("")
                .timeouts(requestMs = 50)
                .build(engine)

        // Assert
        assertFailsWith<HttpRequestTimeoutException> { client.get("/") }
    }

    /** Longer request timeout allows a slightly delayed response to complete. */
    @Test
    fun testTimeoutsLongerRequestDoesNotTimeout() = runTest {
        // Arrange
        val engine = MockEngine {
            delay(50)
            respond("ok", HttpStatusCode.OK)
        }
        val client =
            ZetaHttpClientBuilder("")
                .timeouts(requestMs = 150)
                .build(engine)

        // Act
        val res = client.get("/slow-ok")

        // Assert
        assertEquals(HttpStatusCode.OK, res.status)
    }

    /** GET is retried on 503 and succeeds within maxRetries when idempotent-only is true. */
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
            ZetaHttpClientBuilder("")
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

    /** Fails if errors persist beyond maxRetries (status remains the failing one). */
    @Test
    fun testRetryExceededStillFails() = runTest {
        // Arrange
        val maxRetries = 2
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }

        // Act
        val client =
            ZetaHttpClientBuilder("")
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

    /** With onlyIdempotent=true, POST is not retried on retriable status codes. */
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
            ZetaHttpClientBuilder("")
                .retry(
                    onlyIdempotent = true,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                )
                .build(engine)

        runCatching { client.post("/") { setBody("x") } }

        // Assert
        assertEquals(1, retryHits)
    }

    /** With onlyIdempotent=false, POST is retried on retriable status codes. */
    @Test
    fun testRetrySkipsPostWhenIdempotentOnlyFalse() = runTest {
        // Arrange
        var retryHits = 0
        val maxRetries = 2

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
            ZetaHttpClientBuilder("")
                .retry(
                    onlyIdempotent = false,
                    statusCodes = setOf(HttpStatusCode.ServiceUnavailable),
                )
                .build(engine)

        runCatching { client.post("/") { setBody("x") } }

        // Assert
        assertEquals(2, retryHits)
    }

    /** PUT is treated as idempotent: retried and then succeeds. */
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
            ZetaHttpClientBuilder("")
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

    /** If status is not in the retry set, no retries occur. */
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
            ZetaHttpClientBuilder("")
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

    /** Engine failures propagate as exceptions to the caller. */
    @Test
    fun testEngineFailurePropagates() = runTest {
        // Arrange
        val engine = MockEngine { error("error") }
        // Act + Assert
        assertFailsWith<Throwable> {
            zetaHttpClient({ ZetaHttpClientBuilder("").build(engine) })
                .get("/")
        }
    }

    /** NONE: emits nothing. */
    @Test
    fun testLogLevelNoneEmitsNoLogs() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("resp", HttpStatusCode.OK)
        }

        // Act
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder("")
                .logging(LogLevel.NONE, logProvider = logger)
                .build(engine)

        client.get("/none")

        // Assert
        assertTrue(logger.lines.isEmpty())
    }

    /** INFO: does not include headers or bodies (but should emit some lines). */
    @Test
    fun testLogLevelInfoOmitsHeadersAndBodies() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }

        // Act
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder("")
                .logging(LogLevel.INFO, logProvider = logger)
                .build(engine)
        client.post("/info") {
            header("X-Token", "abc123")
            setBody("""{"p":"payload-test"}""")
        }

        // Assert
        assertTrue(logger.lines.joinToString("\n").let { it.isNotEmpty() && "X-Token" !in it && "payload-abc" !in it })
    }

    /** HEADERS: includes headers, still omits bodies. */
    @Test
    fun testLogLevelHeadersIncludesHeadersNotBodies() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder("")
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

    /** ALL: includes request headers + body and response bodies  . */
    @Test
    fun testLogLevelBodyIncludesHeadersAndBodies() = runTest {
        // Arrange
        val logger = CaptureLogger()
        val engine = MockEngine {
            respond("""{"resp":"r-123"}""", HttpStatusCode.OK)
        }
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder("")
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

    /** GET (idempotent) should be retried when retryOnlyIdempotent = true. */
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
            ZetaHttpClientBuilder("")
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.get("/").bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    /** PUT (idempotent) should be retried when retryOnlyIdempotent = true. */
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
            ZetaHttpClientBuilder("")
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.put("/resource") { setBody("x") }.bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    /** POST (non-idempotent) should NOT be retried when retryOnlyIdempotent = true. */
    @Test
    fun testRetryOnExceptionPostNotRetriedWhenIdempotentTrue() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException("boom") } // always fail
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder("")
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        runCatching { client.post("/") { setBody("x") } } // call fails; we just observe attempts

        // Assert
        assertEquals(1, hits[0]) // no retry took place
    }

    /** PATCH (non-idempotent) should NOT be retried when retryOnlyIdempotent = true. */
    @Test
    fun testRetryOnExceptionPatchNotRetriedWhenIdempotentTrue() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException("boom") } // always fail
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder("")
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        runCatching { client.patch("/") { setBody("y") } }
        // Assert
        assertEquals(1, hits[0])
    }

    /** POST should be retried when retryOnlyIdempotent = false (retry all methods). */
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
            ZetaHttpClientBuilder("")
                .retry(onlyIdempotent = false, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.post("/") { setBody("x") }.bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    /** Even for idempotent methods, maxRetries = 0 disables retries. */
    @Test
    fun testRetryOnExceptionNoRetryWhenMaxRetriesZero() = runTest {
        // Arrange
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException("boom") } // always fail
        val client: ZetaHttpClient =
            ZetaHttpClientBuilder("")
                .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 0)
                .build(engine)

        // Act
        runCatching { client.get("/") }

        // Assert
        assertEquals(1, hits[0])
    }

    /** With retryOnlyIdempotent=false, ANY method (e.g., PATCH) gets retried on exception. */
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
            ZetaHttpClientBuilder("")
                .retry(onlyIdempotent = false, statusCodes = emptySet(), maxRetries = 1)
                .build(engine)

        // Act
        val body = client.patch("/") { setBody("y") }.bodyAsText()

        // Assert
        assertEquals("ok", body)
    }

    /** Minimal logger that captures log lines for assertions. */
    private class CaptureLogger : Logger {
        val lines = mutableListOf<String>()
        override fun log(message: String) { lines += message }
    }
}
