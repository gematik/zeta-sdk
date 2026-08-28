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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationApiImplTest {
    @Test
    fun fetchResourceMetadata_returnsBody_whenOk() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }

        val ktorClient = HttpClient(engine)
        val zetaClient = ZetaHttpClient(ktorClient)

        val builder = object : ZetaHttpClientBuilder() {
            override fun build(newUrl: String): ZetaHttpClient {
                assertEquals("https://example.com/.well-known/", newUrl)
                return zetaClient
            }
        }

        val api = ConfigurationApiImpl(builder)

        // Act
        val result = api.fetchResourceMetadata("https://example.com")

        // Assert
        assertEquals("""{"ok":true}""", result.body)
        assertFalse(result.notModified)
    }

    @Test
    fun fetchResourceMetadata_returnsCacheControlAndETag_whenOk() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.CacheControl to listOf("max-age=120"),
                    HttpHeaders.ETag to listOf("\"etag-123\""),
                ),
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchResourceMetadata("https://example.com")

        // Assert
        assertEquals("""{"ok":true}""", result.body)
        assertEquals(120L, result.maxAgeSeconds)
        assertEquals("\"etag-123\"", result.eTag)
        assertFalse(result.notModified)
    }

    @Test
    fun fetchResourceMetadata_sendsIfNoneMatch_whenETagProvided() = runTest {
        // Arrange
        var ifNoneMatch: String? = null

        val engine = MockEngine { request ->
            ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]

            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
            )
        }

        val api = createApi(engine)

        // Act
        api.fetchResourceMetadata(
            resourceUrl = "https://example.com",
            eTag = "\"etag-123\"",
        )

        // Assert
        assertEquals("\"etag-123\"", ifNoneMatch)
    }

    @Test
    fun fetchResourceMetadata_doesNotSendIfNoneMatch_whenETagMissing() = runTest {
        // Arrange
        var ifNoneMatch: String? = "unexpected"

        val engine = MockEngine { request ->
            ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]

            respond(
                content = "{}",
                status = HttpStatusCode.OK,
            )
        }

        val api = createApi(engine)

        // Act
        api.fetchResourceMetadata("https://example.com")

        // Assert
        assertNull(ifNoneMatch)
    }

    @Test
    fun fetchResourceMetadata_returnsNotModified_when304() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.NotModified,
                headers = headersOf(
                    HttpHeaders.CacheControl to listOf("max-age=300"),
                    HttpHeaders.ETag to listOf("\"etag-123\""),
                ),
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchResourceMetadata(
            resourceUrl = "https://example.com",
            eTag = "\"etag-123\"",
        )

        // Assert
        assertTrue(result.notModified)
        assertNull(result.body)
        assertEquals(300L, result.maxAgeSeconds)
        assertEquals("\"etag-123\"", result.eTag)
    }

    @Test
    fun fetchResourceMetadata_returnsNullMaxAge_when304WithoutCacheControl() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.NotModified,
                headers = headersOf(
                    HttpHeaders.ETag,
                    "\"etag-123\"",
                ),
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchResourceMetadata(
            resourceUrl = "https://example.com",
            eTag = "\"etag-123\"",
        )

        // Assert
        assertTrue(result.notModified)
        assertNull(result.body)
        assertNull(result.maxAgeSeconds)
        assertEquals("\"etag-123\"", result.eTag)
    }

    @Test
    fun fetchResourceMetadata_parsesMaxAge_fromCacheControl() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.CacheControl,
                    "public, max-age=3600, immutable",
                ),
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchResourceMetadata("https://example.com")

        // Assert
        assertEquals(3600L, result.maxAgeSeconds)
    }

    @Test
    fun fetchResourceMetadata_parsesMaxAge_caseInsensitive() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.CacheControl,
                    "MAX-AGE=60",
                ),
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchResourceMetadata("https://example.com")

        // Assert
        assertEquals(60L, result.maxAgeSeconds)
    }

    @Test
    fun fetchResourceMetadata_returnsNullMaxAge_whenCacheControlMissing() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchResourceMetadata("https://example.com")

        // Assert
        assertNull(result.maxAgeSeconds)
    }

    @Test
    fun fetchResourceMetadata_returnsNullMaxAge_whenMaxAgeIsInvalid() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.CacheControl,
                    "max-age=invalid",
                ),
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchResourceMetadata("https://example.com")

        // Assert
        assertNull(result.maxAgeSeconds)
    }

    @Test
    fun fetchResourceMetadata_throws_whenNotOk() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = """{"error":"bad"}""",
                status = HttpStatusCode.BadRequest,
            )
        }

        val api = createApi(engine)

        // Act / Assert
        val error = assertFailsWith<ServiceDiscoveryException> {
            api.fetchResourceMetadata("https://example.com")
        }

        assertEquals(
            "Service discovery failed to load resource: " +
                "https://example.com/.well-known/oauth-protected-resource",
            error.message,
        )
    }

    @Test
    fun fetchResourceMetadata_appendsSubpath_toWellKnownPath() = runTest {
        // Arrange
        var requestedPath: String? = null

        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath

            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }

        val api = createApi(engine) { newUrl ->
            assertEquals(
                "https://example.com/.well-known/",
                newUrl,
            )
        }

        // Act
        val result = api.fetchResourceMetadata(
            "https://example.com",
            "notification-service",
        )

        // Assert
        assertEquals("""{"ok":true}""", result.body)
        assertEquals(
            "/oauth-protected-resource/notification-service",
            requestedPath,
        )
    }

    @Test
    fun fetchResourceMetadata_trimsSubpathSlashes() = runTest {
        // Arrange
        var requestedPath: String? = null

        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond("{}", HttpStatusCode.OK)
        }

        val api = createApi(engine)

        // Act
        api.fetchResourceMetadata(
            "https://example.com",
            "/notification-service/",
        )

        // Assert
        assertEquals(
            "/oauth-protected-resource/notification-service",
            requestedPath,
        )
    }

    @Test
    fun fetchResourceMetadata_usesLegacyPath_whenSubpathBlank() = runTest {
        // Arrange
        var requestedPath: String? = null

        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond("{}", HttpStatusCode.OK)
        }

        val api = createApi(engine)

        // Act
        api.fetchResourceMetadata(
            "https://example.com",
            "",
        )

        // Assert
        assertEquals(
            "/oauth-protected-resource",
            requestedPath,
        )
    }

    @Test
    fun fetchResourceMetadata_reportsSubpathUrl_onError() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                """{"error":"bad"}""",
                HttpStatusCode.NotFound,
            )
        }

        val api = createApi(engine)

        // Act / Assert
        val error = assertFailsWith<ServiceDiscoveryException> {
            api.fetchResourceMetadata(
                "https://example.com",
                "notification-service",
            )
        }

        assertEquals(
            "Service discovery failed to load resource: " +
                "https://example.com/.well-known/" +
                "oauth-protected-resource/notification-service",
            error.message,
        )
    }

    @Test
    fun fetchAuthorizationMetadata_callsCorrectBaseUrl() = runTest {
        // Arrange
        var baseUrlCaptured: String? = null

        val engine = MockEngine {
            respond(
                content = """{"auth":true}""",
                status = HttpStatusCode.OK,
            )
        }

        val api = createApi(engine) {
            baseUrlCaptured = it
        }

        // Act
        val result = api.fetchAuthorizationMetadata(
            "https://auth.example.com",
        )

        // Assert
        assertEquals("""{"auth":true}""", result.body)
        assertEquals(
            "https://auth.example.com/.well-known/",
            baseUrlCaptured,
        )
    }

    @Test
    fun fetchAuthorizationMetadata_usesCorrectPath() = runTest {
        // Arrange
        var requestedPath: String? = null

        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath

            respond(
                content = "{}",
                status = HttpStatusCode.OK,
            )
        }

        val api = createApi(engine)

        // Act
        api.fetchAuthorizationMetadata(
            "https://auth.example.com",
        )

        // Assert
        assertEquals(
            "/oauth-authorization-server",
            requestedPath,
        )
    }

    @Test
    fun fetchAuthorizationMetadata_sendsIfNoneMatch_whenETagProvided() = runTest {
        // Arrange
        var ifNoneMatch: String? = null

        val engine = MockEngine { request ->
            ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]

            respond(
                content = """{"auth":true}""",
                status = HttpStatusCode.OK,
            )
        }

        val api = createApi(engine)

        // Act
        api.fetchAuthorizationMetadata(
            authFqdns = "https://auth.example.com",
            eTag = "\"auth-etag\"",
        )

        // Assert
        assertEquals("\"auth-etag\"", ifNoneMatch)
    }

    @Test
    fun fetchAuthorizationMetadata_doesNotSendIfNoneMatch_whenETagMissing() = runTest {
        // Arrange
        var ifNoneMatch: String? = "unexpected"

        val engine = MockEngine { request ->
            ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]

            respond(
                content = "{}",
                status = HttpStatusCode.OK,
            )
        }

        val api = createApi(engine)

        // Act
        api.fetchAuthorizationMetadata(
            "https://auth.example.com",
        )

        // Assert
        assertNull(ifNoneMatch)
    }

    @Test
    fun fetchAuthorizationMetadata_returnsNotModified_when304() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.NotModified,
                headers = headersOf(
                    HttpHeaders.CacheControl to listOf("max-age=600"),
                    HttpHeaders.ETag to listOf("\"auth-etag\""),
                ),
            )
        }

        val api = createApi(engine)

        // Act
        val result = api.fetchAuthorizationMetadata(
            authFqdns = "https://auth.example.com",
            eTag = "\"auth-etag\"",
        )

        // Assert
        assertTrue(result.notModified)
        assertNull(result.body)
        assertEquals(600L, result.maxAgeSeconds)
        assertEquals("\"auth-etag\"", result.eTag)
    }

    @Test
    fun fetchAuthorizationMetadata_throws_whenNotOk() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.InternalServerError,
            )
        }

        val api = createApi(engine)

        // Act / Assert
        val error = assertFailsWith<ServiceDiscoveryException> {
            api.fetchAuthorizationMetadata(
                "https://auth.example.com",
            )
        }

        assertEquals(
            "Service discovery failed to load resource: " +
                "https://auth.example.com/.well-known/" +
                "oauth-authorization-server",
            error.message,
        )
    }

    @Test
    fun fetchResourceMetadata_keepsCustomPort() = runTest {
        // Arrange
        var baseUrlCaptured: String? = null

        val engine = MockEngine {
            respond("{}", HttpStatusCode.OK)
        }

        val api = createApi(engine) {
            baseUrlCaptured = it
        }

        // Act
        api.fetchResourceMetadata(
            "https://example.com:8443/test",
        )

        // Assert
        assertEquals(
            "https://example.com:8443/.well-known/",
            baseUrlCaptured,
        )
    }

    @Test
    fun fetchResourceMetadata_doesNotKeepDefaultHttpsPort() = runTest {
        // Arrange
        var baseUrlCaptured: String? = null

        val engine = MockEngine {
            respond("{}", HttpStatusCode.OK)
        }

        val api = createApi(engine) {
            baseUrlCaptured = it
        }

        // Act
        api.fetchResourceMetadata(
            "https://example.com:443/test",
        )

        // Assert
        assertEquals(
            "https://example.com/.well-known/",
            baseUrlCaptured,
        )
    }

    private fun createApi(
        engine: MockEngine,
        onBuild: (String) -> Unit = {},
    ): ConfigurationApiImpl {
        val zetaClient = ZetaHttpClient(
            HttpClient(engine),
        )

        val builder = object : ZetaHttpClientBuilder() {
            override fun build(
                newUrl: String,
            ): ZetaHttpClient {
                onBuild(newUrl)
                return zetaClient
            }
        }

        return ConfigurationApiImpl(builder)
    }
}
