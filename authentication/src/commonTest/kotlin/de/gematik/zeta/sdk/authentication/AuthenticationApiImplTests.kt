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

package de.gematik.zeta.sdk.authentication

import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AuthenticationApiImplTests {
    @Test
    fun fetchNonce_returnsDecodedBytesOnSuccess() = runTest {
        // Arrange
        val nonceBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val encodedNonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(nonceBytes)
        val engine = MockEngine {
            respond(
                content = encodedNonce,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act
        val result = api.fetchNonce("https://example.com/nonce")

        // Assert
        assertContentEquals(nonceBytes, result)
    }

    @Test
    fun fetchNonce_throwsAuthenticationExceptionOnError() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "error",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act & Assert
        val exception = assertFailsWith<AuthenticationException> {
            api.fetchNonce("https://example.com/nonce")
        }
        assertEquals("error", exception.message)
    }

    @Test
    fun fetchNonce_throwsAuthenticationExceptionOnUnauthorized() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "unauthorized",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act & Assert
        assertFailsWith<AuthenticationException> {
            api.fetchNonce("https://example.com/nonce")
        }
    }

    @Test
    fun requestAccessToken_returnsAccessTokenResponseOnSuccess() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = validTokenResponseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act
        val response = api.requestAccessToken(
            fromEndpoint = "https://example.com/token",
            accessTokenRequest = createAccessTokenRequest(),
            dpopToken = "dpop-token",
        )

        // Assert
        assertEquals("test-access-token", response.accessToken)
        assertEquals(300, response.expiresIn)
        assertEquals(1800, response.refreshExpires)
        assertEquals("Bearer", response.tokenType)
        assertEquals("0", response.notBeforePolicy)
        assertEquals("session-123", response.sessionState)
        assertEquals("openid", response.scope)
        assertEquals("urn:ietf:params:oauth:token-type:access_token", response.issuedTokenType)
        assertEquals("test-refresh-token", response.refreshToken)
    }

    @Test
    fun requestAccessToken_sendsCorrectDpopHeader() = runTest {
        // Arrange
        val dpopToken = "my-dpop-token"
        var capturedDpopHeader: String? = null
        val engine = MockEngine { request ->
            capturedDpopHeader = request.headers["DPoP"]
            respond(
                content = validTokenResponseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act
        api.requestAccessToken(
            fromEndpoint = "https://example.com/token",
            accessTokenRequest = createAccessTokenRequest(),
            dpopToken = dpopToken,
        )

        // Assert
        assertEquals(dpopToken, capturedDpopHeader)
    }

    @Test
    fun requestAccessToken_throwsRecoverableExceptionOnBadRequest() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "bad request",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act & Assert
        assertFailsWith<RecoverableAuthenticationException> {
            api.requestAccessToken(
                fromEndpoint = "https://example.com/token",
                accessTokenRequest = createAccessTokenRequest(),
                dpopToken = "dpop-token",
            )
        }
    }

    @Test
    fun requestAccessToken_throwsRecoverableExceptionOnUnauthorized() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "unauthorized",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act & Assert
        assertFailsWith<RecoverableAuthenticationException> {
            api.requestAccessToken(
                fromEndpoint = "https://example.com/token",
                accessTokenRequest = createAccessTokenRequest(),
                dpopToken = "dpop-token",
            )
        }
    }

    @Test
    fun requestAccessToken_throwsNonRecoverableExceptionOnForbidden() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "forbidden",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act & Assert
        assertFailsWith<NonRecoverableAuthenticationException> {
            api.requestAccessToken(
                fromEndpoint = "https://example.com/token",
                accessTokenRequest = createAccessTokenRequest(),
                dpopToken = "dpop-token",
            )
        }
    }

    @Test
    fun requestAccessToken_throwsAuthenticationExceptionOnUnexpectedStatus() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "service unavailable",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act & Assert
        val exception = assertFailsWith<AuthenticationException> {
            api.requestAccessToken(
                fromEndpoint = "https://example.com/token",
                accessTokenRequest = createAccessTokenRequest(),
                dpopToken = "dpop-token",
            )
        }
        assertIs<AuthenticationException>(exception)
        // Should not be a subclass
        assertEquals(AuthenticationException::class, exception::class)
    }

    @Test
    fun requestAccessToken_handlesResponseWithMissingOptionalFields() = runTest {
        // Arrange
        val minimalResponse = """
            {
                "access_token": "token",
                "expires_in": 60
            }
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = minimalResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine))

        // Act
        val response = api.requestAccessToken(
            fromEndpoint = "https://example.com/token",
            accessTokenRequest = createAccessTokenRequest(),
            dpopToken = "dpop-token",
        )

        // Assert
        assertEquals("token", response.accessToken)
        assertEquals(60, response.expiresIn)
        assertEquals(0, response.refreshExpires)
        assertEquals("", response.tokenType)
        assertEquals("", response.notBeforePolicy)
        assertEquals("", response.sessionState)
        assertEquals("", response.scope)
        assertEquals("", response.issuedTokenType)
        assertEquals("", response.refreshToken)
    }

    private fun createClient(engine: MockEngine): ZetaHttpClient {
        return ZetaHttpClient(HttpClient(engine))
    }

    private fun createAccessTokenRequest() = AccessTokenRequest(
        grantType = "urn:ietf:params:oauth:grant-type:token-exchange",
        clientId = "test-client",
        subjectToken = "subject-token",
        subjectTokenType = "urn:ietf:params:oauth:token-type:jwt",
        requestedTokenType = "urn:ietf:params:oauth:token-type:access_token",
        clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        clientAssertion = "client-assertion-jwt",
        scope = "openid",
    )

    private val validTokenResponseBody = """
        {
            "access_token": "test-access-token",
            "expires_in": 300,
            "refresh_expires_in": 1800,
            "token_type": "Bearer",
            "not-before-policy": "0",
            "session_state": "session-123",
            "scope": "openid",
            "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
            "refresh_token": "test-refresh-token"
        }
    """.trimIndent()
}
