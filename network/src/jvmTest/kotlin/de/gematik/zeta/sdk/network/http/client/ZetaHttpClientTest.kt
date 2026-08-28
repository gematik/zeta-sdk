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

import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.network.http.client.config.ClientConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyType
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import javax.net.ssl.SSLSocket
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [zetaHttpClient].
 */
class ZetaHttpClientJvmTest {
    private val noDependencies = HttpClientDependencies(
        revocationChecker = null,
    )
    private fun startTlsServerWithCustomRoot(
        host: String = "localhost",
        addIpSan: Boolean = true,
    ): Pair<MockWebServer, HeldCertificate> {
        val bindAddress = InetAddress.getByName("127.0.0.1")
        val root = HeldCertificate.Builder().certificateAuthority(1).build()
        val certBuilder = HeldCertificate.Builder()
            .signedBy(root)
            .addSubjectAlternativeName(host)
        if (addIpSan) certBuilder.addSubjectAlternativeName("127.0.0.1")
        val serverCert = certBuilder.build()
        val serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCert, root.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverHandshake.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            start(bindAddress, 0)
        }
        return server to root
    }

    private fun MockWebServer.ipv4Url(path: String = "/") =
        "https://127.0.0.1:$port$path"

    @Test
    fun testBaseUrlAppliedToRelativeRequests() = runTest {
        val expectedHost = "dummy.example.org"
        var seenHost = ""
        val engine = MockEngine { req ->
            seenHost = req.url.host
            respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder("http://$expectedHost").build(engine)
        client.get("/hellozeta")

        assertEquals(expectedHost, seenHost)
    }

    @Test
    fun testBaseUrlWithPathPrefixesRelativeRequests() = runTest {
        var seenPath = ""
        val expectedEndpoint = "/testfachdienst/hellozeta"
        val engine = MockEngine { req ->
            seenPath = req.url.encodedPath
            respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder("http://dummy.example.org").build(engine)
        client.get(expectedEndpoint)

        assertEquals(expectedEndpoint, seenPath)
    }

    @Test
    fun testTimeoutsShortRequestTimesOut() = runTest {
        val engine = MockEngine { delay(150.milliseconds); respond("ok", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().timeouts(requestMs = 50).build(engine)

        assertFailsWith<HttpRequestTimeoutException> { client.get("/") }
    }

    @Test
    fun testTimeoutsLongerRequestDoesNotTimeout() = runTest {
        val engine = MockEngine { delay(50.milliseconds); respond("ok", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().timeouts(requestMs = 150).build(engine)
        val res = client.get("/slow-ok")

        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun testRetryGetSucceedsWithinMaxRetries() = runTest {
        var retryHits = 0
        val maxRetries = 2
        val engine = MockEngine {
            if (retryHits++ < 2) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = maxRetries)
            .build(engine)
        val body = client.get("/").bodyAsText()

        assertEquals("ok", body)
    }

    @Test
    fun testRetryExceededStillFails() = runTest {
        val maxRetries = 2
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = maxRetries)
            .build(engine)
        val res = client.get("/")

        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }

    @Test
    fun testRetrySkipsPostWhenIdempotentOnlyTrue() = runTest {
        var retryHits = 0
        val engine = MockEngine { retryHits++; respond("", HttpStatusCode.TooManyRequests) }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = setOf(HttpStatusCode.ServiceUnavailable))
            .build(engine)
        runCatching { client.post("/") { setBody("x") } }

        assertEquals(1, retryHits)
    }

    @Test
    fun testRetrySkipsPostWhenIdempotentOnlyFalse() = runTest {
        var retryHits = 0
        val engine = MockEngine {
            retryHits++
            if (retryHits++ == 0) {
                respond("", HttpStatusCode.TooManyRequests)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = false, statusCodes = setOf(HttpStatusCode.ServiceUnavailable))
            .build(engine)
        runCatching { client.post("/") { setBody("x") } }

        assertEquals(2, retryHits)
    }

    @Test
    fun testRetryPutConsideredIdempotentWhenIdempotentOnlyTrue() = runTest {
        var retryHits = 0
        val maxRetries = 1
        val engine = MockEngine {
            if (retryHits++ == 0) {
                respond("", HttpStatusCode.ServiceUnavailable)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = false, statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = maxRetries)
            .build(engine)
        client.put("/resource")

        assertEquals(2, retryHits)
    }

    @Test
    fun testRetryStatusNotInSetDoesNotRetry() = runTest {
        var retryHits = 0
        val maxRetries = 3
        val engine = MockEngine { retryHits++; respond("", HttpStatusCode.NotFound) }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = false, statusCodes = setOf(HttpStatusCode.ServiceUnavailable), maxRetries = maxRetries)
            .build(engine)
        client.get("/missing")

        assertEquals(1, retryHits)
    }

    @Test
    fun testEngineFailurePropagates() = runTest {
        val engine = MockEngine { error("error") }

        assertFailsWith<Throwable> {
            zetaHttpClient({ ZetaHttpClientBuilder().build(engine) }, noDependencies).get("/")
        }
    }

    @Test
    fun testLogLevelNoneEmitsNoLogs() = runTest {
        val logger = CaptureLogger()
        val engine = MockEngine { respond("resp", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().logging(LogLevel.NONE, logProvider = logger).build(engine)
        client.get("/none")
        assertTrue(logger.lines.isEmpty())
    }

    @Test
    fun testLogLevelInfoOmitsHeadersAndBodies() = runTest {
        val logger = CaptureLogger()
        val engine = MockEngine { respond("""{"ok":true}""", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().logging(LogLevel.INFO, logProvider = logger).build(engine)
        client.post("/info") {
            header("X-Token", "abc123")
            setBody("""{"p":"payload-test"}""")
        }

        assertTrue(logger.lines.joinToString("\n").let { it.isNotEmpty() && "X-Token" !in it && "payload-abc" !in it })
    }

    @Test
    fun testLogLevelHeadersIncludesHeadersNotBodies() = runTest {
        val logger = CaptureLogger()
        val engine = MockEngine { respond("""{"ok":true}""", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().logging(LogLevel.HEADERS, logProvider = logger).build(engine)
        client.post("/header") {
            header("X-Token", "abc123")
            setBody("""{"p":"payload-test"}""")
        }

        assertTrue(logger.lines.joinToString("\n").let { it.isNotEmpty() && "X-Token" in it && "payload-abc" !in it })
    }

    @Test
    fun testLogLevelBodyIncludesHeadersAndBodies() = runTest {
        val logger = CaptureLogger()
        val engine = MockEngine { respond("""{"resp":"r-123"}""", HttpStatusCode.OK) }
        val client = ZetaHttpClientBuilder().logging(LogLevel.ALL, logProvider = logger).build(engine)
        client.post("/body") {
            header("X-Token", "abc123")
            setBody("""{"p":"payload-test"}""")
            contentType(ContentType.Application.Json)
        }

        assertTrue(
            logger.lines.joinToString("\n").let {
                it.isNotEmpty() && "X-Token" in it && "payload-test" in it && "r-123" in it
            },
        )
    }

    @Test
    fun testRetryOnExceptionGetRetriedWhenIdempotentTrue() = runTest {
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) throw IOException() else respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        val body = client.get("/").bodyAsText()

        assertEquals("ok", body)
    }

    @Test
    fun testRetryOnExceptionPutRetriedWhenIdempotentTrue() = runTest {
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) throw IOException() else respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        val body = client.put("/resource") { setBody("x") }.bodyAsText()

        assertEquals("ok", body)
    }

    @Test
    fun testRetryOnExceptionPostNotRetriedWhenIdempotentTrue() = runTest {
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException() }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        runCatching { client.post("/") { setBody("x") } }

        assertEquals(1, hits[0])
    }

    @Test
    fun testRetryOnExceptionPatchNotRetriedWhenIdempotentTrue() = runTest {
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException() }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        runCatching { client.patch("/") { setBody("y") } }

        assertEquals(1, hits[0])
    }

    @Test
    fun testRetryOnExceptionPostRetriedWhenIdempotentFalse() = runTest {
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) throw IOException() else respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = false, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        val body = client.post("/") { setBody("x") }.bodyAsText()

        assertEquals("ok", body)
    }

    @Test
    fun testRetryOnExceptionNoRetryWhenMaxRetriesZero() = runTest {
        val hits = intArrayOf(0)
        val engine = MockEngine { hits[0]++; throw IOException() }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = true, statusCodes = emptySet(), maxRetries = 0)
            .build(engine)
        runCatching { client.get("/") }

        assertEquals(1, hits[0])
    }

    @Test
    fun testRetryOnExceptionPatchRetriedWhenIdempotentFalse() = runTest {
        val hits = intArrayOf(0)
        val engine = MockEngine {
            if (hits[0]++ == 0) throw IOException() else respond("ok", HttpStatusCode.OK)
        }
        val client = ZetaHttpClientBuilder()
            .retry(onlyIdempotent = false, statusCodes = emptySet(), maxRetries = 1)
            .build(engine)
        val body = client.patch("/") { setBody("y") }.bodyAsText()

        assertEquals("ok", body)
    }

    @Test
    fun hostOf_extractsHost_fullUrl() {
        assertEquals("api.example.com", hostOf("https://api.example.com/path"))
    }

    @Test
    fun hostOf_extractsHost_urlWithoutProtocol() {
        assertEquals("api.example.com", hostOf("api.example.com"))
    }

    @Test
    fun hostOf_extractsHost_urlWithPort() {
        assertEquals("api.example.com", hostOf("https://api.example.com:8080/path"))
    }

    @Test
    fun hostOf_convertsToLowercase_uppercaseHost() {
        assertEquals("api.example.com", hostOf("https://API.EXAMPLE.COM"))
    }

    @Test
    fun hostOf_removesTrailingDot_hostWithDot() {
        assertEquals("api.example.com", hostOf("https://api.example.com."))
    }

    @Test
    fun hostOf_trimsWhitespace_hostWithSpaces() {
        assertEquals("api.example.com", hostOf("  https://api.example.com  "))
    }

    @Test
    fun hostOf_handlesDoubleSlashPrefix_urlWithoutProtocol() {
        assertEquals("api.example.com", hostOf("//api.example.com/path"))
    }

    @Test
    fun hostOf_extractsHost_subdomain() {
        assertEquals("dev.api.example.com", hostOf("https://dev.api.example.com"))
    }

    @Test
    fun delete_performsDeleteRequest_urlString() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent)
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val response = client.delete("/test")

        assertEquals(204, response.status.value)
    }

    @Test
    fun delete_performsDeleteRequest_urlObject() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            respond(content = ByteReadChannel(""), status = HttpStatusCode.OK)
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val response = client.delete(Url("https://example.com/test"))

        assertNotNull(response)
    }

    @Test
    fun request_performsRequest_configBlockOnly() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            respond(content = ByteReadChannel(""), status = HttpStatusCode.OK)
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val response = client.request { method = HttpMethod.Get; url("/test") }

        assertNotNull(response)
    }

    @Test
    fun request_performsRequest_urlString() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("/test", request.url.encodedPath)
            respond(content = ByteReadChannel(""), status = HttpStatusCode.OK)
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val response = client.request("/test") { method = HttpMethod.Get }

        assertNotNull(response)
    }

    @Test
    fun request_performsRequest_urlObject() = runTest {
        val mockEngine = MockEngine { respond(content = ByteReadChannel(""), status = HttpStatusCode.OK) }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val response = client.request(Url("https://example.com/test")) { method = HttpMethod.Get }

        assertNotNull(response)
    }

    @Test
    fun submitForm_sendsFormData_urlString() = runTest {
        var isFormData = false
        val mockEngine = MockEngine { request ->
            isFormData = request.body is FormDataContent
            respond(content = ByteReadChannel(""), status = HttpStatusCode.OK)
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val params = Parameters.build { append("key", "value") }
        val response = client.submitForm("/test", params)

        assertTrue(isFormData)
        assertNotNull(response)
    }

    @Test
    fun submitForm_encodesInQuery_whenFlagSet() = runTest {
        var queryFound = false
        val mockEngine = MockEngine { request ->
            queryFound = request.url.parameters.contains("key")
            respond(content = ByteReadChannel(""), status = HttpStatusCode.OK)
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val params = Parameters.build { append("key", "value") }
        client.submitForm("/test", params, encodeInQuery = true)

        assertTrue(queryFound)
    }

    @Test
    fun submitForm_appliesConfigBlock_customHeaders() = runTest {
        var headerFound = false
        val mockEngine = MockEngine { request ->
            headerFound = request.headers.contains("X-Custom", "value")
            respond(content = ByteReadChannel(""), status = HttpStatusCode.OK)
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val params = Parameters.build { append("key", "value") }
        client.submitForm("/test", params) { headers.append("X-Custom", "value") }

        assertTrue(headerFound)
    }

    @Test
    fun close_closesUnderlyingClient_doesNotThrow() {
        val mockEngine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        client.close()
    }

    @Test
    fun useRaw_providesAccessToDelegate_returnsValue() {
        val mockEngine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val result = client.useRaw { "test value" }

        assertEquals("test value", result)
    }

    @Test
    fun get_returnsZetaHttpResponse_withCorrectStatus() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("test body"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("text/plain")),
            )
        }
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val response = client.get("/test")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("test body", response.bodyAsText())
    }

    @Test
    fun post_returnsZetaHttpResponse_withHeaders() = runTest {
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
        val client = ZetaHttpClient(HttpClient(mockEngine))
        val response = client.post("/test")

        assertEquals("custom-value", response.headers["X-Custom-Header"])
        assertEquals("application/json", response.headers["Content-Type"])
    }

    @Test
    fun zetaHttpClient_installsContentNegotiation_always() {
        val client = zetaHttpClient(configure = { contentNegotiation = true }, dependencies = noDependencies)
        val hasPlugin = client.useRaw { pluginOrNull(ContentNegotiation) != null }

        assertTrue(hasPlugin)
        client.close()
    }

    @Test
    fun zetaHttpClient_appliesExtras_whenProvided() {
        var extrasApplied = false
        val client = zetaHttpClient(configure = {}, addExtras = { extrasApplied = true }, dependencies = noDependencies)

        assertTrue(extrasApplied)
        client.close()
    }

    @Test
    fun zetaHttpClient_usesInjectedEngine_whenProvided() {
        val mockEngine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }
        val client = zetaHttpClient(configure = { engineFactory = { mockEngine } }, noDependencies)

        assertNotNull(client)
        client.close()
    }

    @Test
    fun buildPlatformClient_disableServerValidation_acceptsAnyCertificate() = runTest {
        // Arrange
        val (server, _) = startTlsServerWithCustomRoot()
        try {
            val client = ZetaHttpClientBuilder()
                .disableServerValidation(true)
                .build()

            // Act
            val response = client.get(server.ipv4Url())

            // Assert
            assertEquals(HttpStatusCode.OK, response.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun applyProxy_setsSystemProperties_whenSocksWithCredentials() {
        // Arrange
        val cfg = ClientConfig().apply {
            network = network.copy(
                proxyConfig = ProxyConfig(
                    type = ProxyType.SOCKS,
                    host = "127.0.0.1",
                    port = 1080,
                    username = "user",
                    password = "pass".toCharArray(),
                ),
            )
        }

        // Act
        val client = buildPlatformClient(cfg, noDependencies) {}

        // Assert
        assertEquals("user", System.getProperty("java.net.socks.username"))
        assertEquals("pass", System.getProperty("java.net.socks.password"))
        client.close()
    }

    @Test
    fun buildPlatformClient_setsSslDebug_whenSslVerboseEnabled() {
        // Arrange
        val cfg = ClientConfig().apply {
            security = security.copy(sslVerbose = true)
        }

        // Act
        val client = buildPlatformClient(cfg, noDependencies) {}

        // Assert
        assertEquals("ssl:handshake", System.getProperty("javax.net.debug"))
        client.close()
    }

    @Test
    fun buildPlatformClient_doesNotSetSslDebug_whenSslVerboseDisabled() {
        // Arrange
        System.clearProperty("javax.net.debug")
        val cfg = ClientConfig().apply {
            security = security.copy(sslVerbose = false)
        }

        // Act
        val client = buildPlatformClient(cfg, noDependencies) {}

        // Assert
        assertNull(System.getProperty("javax.net.debug"))
        client.close()
    }

    @Test
    fun checkServerTrusted_revocationCheckFails_throwsCertificateException() = runTest {
        // Arrange
        val (server, root) = startTlsServerWithCustomRoot()
        try {
            val handler = mockk<RevocationHandler>()
            every { handler.getNextUpdateEpochSeconds(any(), any(), any()) } returns Clock.System.now().epochSeconds + 3600
            every { handler.validate(any(), any(), any()) } throws IllegalStateException("Certificate is revoked")

            val revocationChecker = RevocationChecker(
                storage = RevocationStorage(InMemoryStorage(), ResourceScope("https://localhost", emptyList())),
                httpClient = HttpClient(MockEngine { error("unexpected network call: ${it.url}") }),
                handler = handler,
            )

            val client = ZetaHttpClientBuilder()
                .disableServerValidation(false)
                .addCaPem(root.certificatePem())
                .revocationChecker(revocationChecker)
                .build()

            // Act & Assert
            assertFailsWith<Exception> {
                client.get(server.ipv4Url())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun buildHttpClient_setsProxyAuthorizationHeader_whenHttpProxyWithCredentials() = runTest {
        // Arrange
        val proxyServer = MockWebServer()
        proxyServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        proxyServer.start()

        val cfg = ClientConfig().apply {
            baseUrlOverride = "http://example.com"
            security = security.copy(disableServerValidation = true)
            network = network.copy(
                proxyConfig = ProxyConfig(
                    type = ProxyType.HTTP,
                    host = "127.0.0.1",
                    port = proxyServer.port,
                    username = "user",
                    password = "pass".toCharArray(),
                ),
            )
        }

        // Act
        val client = buildHttpClient(cfg, noDependencies) {}
        client.get("http://example.com/test")
        client.close()

        // Assert
        val request = proxyServer.takeRequest()
        val expected = "Basic " + Base64.encode("user:pass".encodeToByteArray())
        assertEquals(expected, request.getHeader("Proxy-Authorization"))
        proxyServer.shutdown()
    }

    @Test
    fun applyProxy_returnsBuilder_whenProxyConfigIsNull() {
        val cfg = ClientConfig().apply {
            network = network.copy(proxyConfig = null)
        }
        val client = buildPlatformClient(cfg, noDependencies) {}
        assertNotNull(client)
        client.close()
    }

    @Test
    fun applyProxy_setsHttpProxy_withoutCredentials() = runTest {
        val proxyServer = MockWebServer()
        proxyServer.enqueue(MockResponse().setResponseCode(200))
        proxyServer.start()

        val cfg = ClientConfig().apply {
            baseUrlOverride = "https://example.com"
            network = network.copy(
                proxyConfig = ProxyConfig(
                    type = ProxyType.HTTP,
                    host = "127.0.0.1",
                    port = proxyServer.port,
                    username = null,
                    password = null,
                ),
            )
        }

        val client = buildPlatformClient(cfg, noDependencies) {}

        runCatching {
            client.get("https://example.com/test")
        }

        client.close()

        val request = proxyServer.takeRequest()
        assertEquals("CONNECT", request.method)
        assertNull(request.getHeader("Proxy-Authorization"))
        proxyServer.shutdown()
    }

    @Test
    fun applyProxy_setsProxyAuthenticator_whenHttpProxyWithCredentials() = runTest {
        val proxyServer = MockWebServer()
        proxyServer.enqueue(
            MockResponse()
                .setResponseCode(407)
                .setHeader("Proxy-Authenticate", "Basic realm=\"proxy\""),
        )
        proxyServer.enqueue(MockResponse().setResponseCode(200))
        proxyServer.start()

        val cfg = ClientConfig().apply {
            baseUrlOverride = "https://example.com"
            network = network.copy(
                proxyConfig = ProxyConfig(
                    type = ProxyType.HTTP,
                    host = "127.0.0.1",
                    port = proxyServer.port,
                    username = "user",
                    password = "pass".toCharArray(),
                ),
            )
        }

        val client = buildPlatformClient(cfg, noDependencies) {}

        runCatching {
            client.get("https://example.com/test")
        }

        client.close()

        proxyServer.takeRequest()
        val authRequest = proxyServer.takeRequest()
        val expected = "Basic " + Base64.encode("user:pass".encodeToByteArray())
        assertEquals("CONNECT", authRequest.method)
        assertEquals(expected, authRequest.getHeader("Proxy-Authorization"))
        proxyServer.shutdown()
    }

    @Test
    fun applyProxy_setsSocksProxy_withoutCredentials() {
        val cfg = ClientConfig().apply {
            network = network.copy(
                proxyConfig = ProxyConfig(
                    type = ProxyType.SOCKS,
                    host = "127.0.0.1",
                    port = 1080,
                    username = null,
                    password = null,
                ),
            )
        }

        val client = buildPlatformClient(cfg, noDependencies) {}
        assertNotNull(client)
        client.close()

        assertNull(System.getProperty("java.net.socks.username").takeIf { it == "null" })
    }

    @Test
    fun buildInsecureTls_allows_tls13_negotiation() {
        val (factory, _) = buildInsecureTls()

        val socket = factory.createSocket() as SSLSocket
        val enabledProtocols = socket.enabledProtocols.toList()

        assertTrue(
            "TLSv1.3" in enabledProtocols,
            "Expected TLSv1.3 to be negotiable, got: $enabledProtocols",
        )
        socket.close()
    }

    private class CaptureLogger : Logger {
        val lines = mutableListOf<String>()
        override fun log(message: String) { lines += message }
    }
}
