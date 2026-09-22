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
package de.gematik.zeta.sdk.authentication.oidc

import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AuthenticationApiImpl
import de.gematik.zeta.sdk.authentication.AuthenticationException
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OidcTokenIssuanceTests {
    @Test
    fun exchangeEmailBindingToken_returnsAndStoresAccessTokenOnSuccess() = runTest {
        // Arrange
        val engine = MockEngine { request ->
            assertEquals(TOKEN_ENDPOINT, request.url.toString())
            jsonResponse(
                """{"access_token":"exchanged-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"exchanged-refresh"}""",
            )
        }
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        val result = sut.exchangeEmailBindingToken("binding-token", fakeRequest())

        // Assert
        assertEquals("exchanged-token", result)
        assertEquals("exchanged-token", storage.getAccessToken())
        assertEquals("exchanged-refresh", storage.getRefreshToken())
    }

    @Test
    fun exchangeEmailBindingToken_savesEmptyRefreshToken_whenMissingFromResponse() = runTest {
        // Arrange
        val engine = MockEngine {
            jsonResponse(
                """{"access_token":"exchanged-token","expires_in":300,"token_type":"DPoP","scope":"openid"}""",
            )
        }
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        sut.exchangeEmailBindingToken("binding-token", fakeRequest())

        // Assert
        assertEquals("", storage.getRefreshToken())
    }

    @Test
    fun completeEmailBinding_collectEmail_bindsThenVerifiesThenExchanges() = runTest {
        // Arrange
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                BIND_EMAIL_ENDPOINT -> jsonResponse(
                    """{"challenge_type":"email_otp","email_hint":"t*@e*.com"}""",
                    HttpStatusCode.Accepted,
                )
                VERIFY_ENDPOINT -> jsonResponse("""{"status":"bound"}""")
                TOKEN_ENDPOINT -> jsonResponse(
                    """{"access_token":"final-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh"}""",
                )
                else -> error("Unexpected request to ${request.url}")
            }
        }
        val sut = issuance(engine)
        val otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = "test@example.com"
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission {
                assertEquals("t*@e*.com", emailHint)
                assertEquals(false, rejected)
                return OtpSubmission.Otp("123456")
            }
        }
        val pending = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "zeta:email-verify",
            emailHint = null,
            bindingMode = "collect_email",
        )

        // Act
        val result = sut.completeEmailBinding(pending, fakeRequest(), otpCallback)

        // Assert
        assertEquals("final-token", result)
    }

    @Test
    fun completeEmailBinding_verifyOtp_skipsBindEmailCall_whenBindingModeIsNotCollectEmail() = runTest {
        // Arrange
        var bindEmailCalled = false
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                BIND_EMAIL_ENDPOINT -> {
                    bindEmailCalled = true
                    jsonResponse("""{"challenge_type":"email_otp"}""", HttpStatusCode.Accepted)
                }
                VERIFY_ENDPOINT -> jsonResponse("""{"status":"bound"}""")
                TOKEN_ENDPOINT -> jsonResponse(
                    """{"access_token":"final-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh"}""",
                )
                else -> error("Unexpected request to ${request.url}")
            }
        }
        val sut = issuance(engine)
        val otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = error("should not be called")
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission =
                OtpSubmission.Otp("654321")
        }
        val pending = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "zeta:email-verify",
            emailHint = "t*@e*.com",
            bindingMode = "verify_otp",
        )

        // Act
        sut.completeEmailBinding(pending, fakeRequest(), otpCallback)

        // Assert
        assertEquals(false, bindEmailCalled)
    }

    @Test
    fun completeEmailBinding_resend_updatesEmailHintForSubsequentAwaitOtpCall() = runTest {
        // Arrange
        var callCount = 0
        val hintsSeen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                RESEND_ENDPOINT -> jsonResponse(
                    """{"challenge_type":"email_otp","email_hint":"hint-after-resend"}""",
                    HttpStatusCode.Accepted,
                )
                VERIFY_ENDPOINT -> jsonResponse("""{"status":"bound"}""")
                TOKEN_ENDPOINT -> jsonResponse(
                    """{"access_token":"final-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh"}""",
                )
                else -> error("Unexpected request to ${request.url}")
            }
        }
        val sut = issuance(engine)
        val otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = error("should not be called")
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission {
                hintsSeen.add(emailHint)
                callCount++
                return if (callCount == 1) OtpSubmission.Resend else OtpSubmission.Otp("222222")
            }
        }
        val pending = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "zeta:email-verify",
            emailHint = "hint-before-resend",
            bindingMode = "verify_otp",
        )

        // Act
        sut.completeEmailBinding(pending, fakeRequest(), otpCallback)

        // Assert
        assertEquals(listOf<String?>("hint-before-resend", "hint-after-resend"), hintsSeen)
    }

    @Test
    fun completeEmailBinding_throws_whenBindEmailReturnsUnexpectedChallengeType() = runTest {
        // Arrange
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                BIND_EMAIL_ENDPOINT -> jsonResponse(
                    """{"challenge_type":"unexpected_type"}""",
                    HttpStatusCode.Accepted,
                )
                else -> error("Unexpected request to ${request.url}")
            }
        }
        val sut = issuance(engine)
        val otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = "test@example.com"
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission =
                error("should not be called")
        }
        val pending = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "zeta:email-verify",
            emailHint = null,
            bindingMode = "collect_email",
        )

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.completeEmailBinding(pending, fakeRequest(), otpCallback)
        }
    }

    @Test
    fun completeEmailBinding_throws_whenVerifyReturnsUnexpectedStatus() = runTest {
        // Arrange
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                VERIFY_ENDPOINT -> jsonResponse("""{"status":"pending"}""")
                else -> error("Unexpected request to ${request.url}")
            }
        }
        val sut = issuance(engine)
        val otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = error("should not be called")
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission =
                OtpSubmission.Otp("123456")
        }
        val pending = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "zeta:email-verify",
            emailHint = "t*@e*.com",
            bindingMode = "verify_otp",
        )

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.completeEmailBinding(pending, fakeRequest(), otpCallback)
        }
    }

    @Test
    fun authorize_returnsCode_whenCallbackReturnsMatchingState() = runTest {
        // Arrange
        val callback = AuthenticationCallback { clientId, requestUri ->
            assertEquals("test-client", clientId)
            assertEquals("urn:ietf:params:oauth:request_uri:abc", requestUri)
            AuthenticationCallback.AuthInfo(
                finalUrl = "http://localhost:8090/cb/demo/app?code=auth-code-123&state=expected-state",
            )
        }
        val sut = issuanceWithCallback(callback)

        // Act
        val code = sut.authorize(
            clientId = "test-client",
            requestUri = "urn:ietf:params:oauth:request_uri:abc",
            expectedState = "expected-state",
        )

        // Assert
        assertEquals("auth-code-123", code)
    }

    @Test
    fun authorize_throws_whenAuthenticationCallbackIsNull() = runTest {
        // Arrange
        val sut = issuanceWithCallback(callback = null)

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            sut.authorize(clientId = "test-client", requestUri = "urn:...", expectedState = "state")
        }
        assertEquals("authenticationCallback has to be setup", exception.message)
    }

    @Test
    fun authorize_throws_whenCallbackReturnsNullFinalUrl() = runTest {
        // Arrange
        val callback = AuthenticationCallback { _, _ -> AuthenticationCallback.AuthInfo(finalUrl = null) }
        val sut = issuanceWithCallback(callback)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.authorize(clientId = "test-client", requestUri = "urn:...", expectedState = "state")
        }
    }

    @Test
    fun authorize_throws_whenRedirectUrlHasNoCode() = runTest {
        // Arrange
        val callback = AuthenticationCallback { _, _ ->
            AuthenticationCallback.AuthInfo(
                finalUrl = "http://localhost:8090/cb/demo/app?error=access_denied&error_description=User+cancelled&state=expected-state",
            )
        }
        val sut = issuanceWithCallback(callback)

        // Act & Assert
        val exception = assertFailsWith<IllegalStateException> {
            sut.authorize(clientId = "test-client", requestUri = "urn:...", expectedState = "expected-state")
        }
        assertEquals("authorization failed: access_denied - User cancelled", exception.message)
    }

    @Test
    fun authorize_throws_whenReturnedStateDoesNotMatchExpectedState() = runTest {
        // Arrange
        val callback = AuthenticationCallback { _, _ ->
            AuthenticationCallback.AuthInfo(
                finalUrl = "http://localhost:8090/cb/demo/app?code=auth-code-123&state=wrong-state",
            )
        }
        val sut = issuanceWithCallback(callback)

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            sut.authorize(clientId = "test-client", requestUri = "urn:...", expectedState = "expected-state")
        }
        assertEquals("state mismatch: expected expected-state but got wrong-state", exception.message)
    }

    @Test
    fun authorize_passesClientIdAndRequestUriToCallback() = runTest {
        // Arrange
        var capturedClientId: String? = null
        var capturedRequestUri: String? = null
        val callback = AuthenticationCallback { clientId, requestUri ->
            capturedClientId = clientId
            capturedRequestUri = requestUri
            AuthenticationCallback.AuthInfo(finalUrl = "http://localhost:8090/cb/demo/app?code=c&state=s")
        }
        val sut = issuanceWithCallback(callback)

        // Act
        sut.authorize(clientId = "my-client-id", requestUri = "urn:my-request-uri", expectedState = "s")

        // Assert
        assertEquals("my-client-id", capturedClientId)
        assertEquals("urn:my-request-uri", capturedRequestUri)
    }

    private fun issuanceWithCallback(callback: AuthenticationCallback?): OidcTokenIssuance {
        val neverCalledEngine = MockEngine { error("authorize() must not perform any HTTP request") }
        return OidcTokenIssuance(
            authApi = AuthenticationApiImpl(ZetaHttpClient(HttpClient(neverCalledEngine)), clock = SystemZetaClock),
            authStorage = AuthenticationStorageImpl(InMemoryStorage(), resourceScope = ResourceScope("", emptyList())),
            authenticationCallback = callback,
            resolveParEndpoint = { error("not used") },
            resolveBindEmailEndpoint = { error("not used") },
            resolveResendEmailEndpoint = { error("not used") },
            resolveVerifyEmailEndpoint = { error("not used") },
            clock = { 1000L },
        )
    }

    @Test
    fun requestPar_returnsParResponse_onSuccess() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = """{"request_uri":"urn:ietf:params:oauth:request_uri:abc123","expires_in":60}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine), SystemZetaClock)

        // Act
        val response = api.requestPar(
            endpoint = PAR_ENDPOINT,
            parameters = Parameters.build { append("response_type", "code") },
        )

        // Assert
        assertEquals("urn:ietf:params:oauth:request_uri:abc123", response.requestUri)
        assertEquals(60, response.expiresIn)
    }

    @Test
    fun requestPar_sendsRequestToGivenEndpoint() = runTest {
        // Arrange
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"request_uri":"urn:...","expires_in":60}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine), SystemZetaClock)

        // Act
        api.requestPar(
            endpoint = PAR_ENDPOINT,
            parameters = Parameters.build { append("scope", "openid") },
        )

        // Assert
        assertEquals(PAR_ENDPOINT, capturedUrl)
    }

    @Test
    fun requestPar_throwsAuthenticationException_withServerErrorDetails_onFailure() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = """{"error":"invalid_request","error_description":"redirect_uri not found in entity statement"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine), SystemZetaClock)

        // Act & Assert
        val exception = assertFailsWith<AuthenticationException> {
            api.requestPar(
                endpoint = PAR_ENDPOINT,
                parameters = Parameters.build { append("response_type", "code") },
            )
        }
        assertTrue(exception.message!!.contains("400"))
        assertTrue(exception.message!!.contains("invalid_request"))
        assertTrue(exception.message!!.contains("redirect_uri not found in entity statement"))
    }

    @Test
    fun requestPar_throwsAuthenticationException_withFallbackMessage_whenErrorBodyIsNotParseable() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = "not json",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val api = AuthenticationApiImpl(createClient(engine), SystemZetaClock)

        // Act & Assert
        val exception = assertFailsWith<AuthenticationException> {
            api.requestPar(
                endpoint = PAR_ENDPOINT,
                parameters = Parameters.build { append("response_type", "code") },
            )
        }
        assertTrue(exception.message!!.contains("500"))
        assertTrue(exception.message!!.contains("unknown_error"))
        assertTrue(exception.message!!.contains("no description"))
    }

    @Test
    fun postPar_returnsRequestUri_onSuccess() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = """{"request_uri":"urn:ietf:params:oauth:request_uri:abc123","expires_in":60}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sut = issuance(engine)

        // Act
        val requestUri = sut.postPar(
            provider = oidcProvider(),
            codeChallenge = "challenge-value",
            state = "state-value",
            clientAssertion = "assertion-jwt",
            scope = "openid",
        )

        // Assert
        assertEquals("urn:ietf:params:oauth:request_uri:abc123", requestUri)
    }

    @Test
    fun postPar_propagatesAuthenticationException_onFailure() = runTest {
        // Arrange
        val engine = MockEngine {
            respond(
                content = """{"error":"invalid_request","error_description":"redirect_uri not found in entity statement"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sut = issuance(engine)

        // Act & Assert
        assertFailsWith<AuthenticationException> {
            sut.postPar(
                provider = oidcProvider(),
                codeChallenge = "challenge-value",
                state = "state-value",
                clientAssertion = "assertion-jwt",
                scope = "openid",
            )
        }
    }
}

private const val BIND_EMAIL_ENDPOINT = "https://zeta-kind.local/zeta/identity/bind-email"
private const val RESEND_ENDPOINT = "https://zeta-kind.local/zeta/identity/bind-email/resend"
private const val VERIFY_ENDPOINT = "https://zeta-kind.local/zeta/identity/bind-email/verify"
private const val TOKEN_ENDPOINT = "https://zeta-kind.local/token"
private const val PAR_ENDPOINT = "https://zeta-kind.local/protocol/openid-connect/ext/par/request"

private fun createClient(engine: MockEngine): ZetaHttpClient = ZetaHttpClient(HttpClient(engine))

private fun newAuthStorage(): AuthenticationStorage =
    AuthenticationStorageImpl(InMemoryStorage(), resourceScope = ResourceScope("", emptyList()))

private fun issuance(engine: MockEngine, storage: AuthenticationStorage = newAuthStorage()): OidcTokenIssuance {
    val api = AuthenticationApiImpl(createClient(engine), SystemZetaClock)
    return OidcTokenIssuance(
        authApi = api,
        authStorage = storage,
        authenticationCallback = null,
        resolveParEndpoint = { PAR_ENDPOINT },
        resolveBindEmailEndpoint = { BIND_EMAIL_ENDPOINT },
        resolveResendEmailEndpoint = { RESEND_ENDPOINT },
        resolveVerifyEmailEndpoint = { VERIFY_ENDPOINT },
        clock = { 1000L },
    )
}

private fun fakeRequest(): TokenIssuanceRequest = TokenIssuanceRequest(
    tokenEndpoint = TOKEN_ENDPOINT,
    nonce = ByteArray(0),
    dpopProvider = { _, _ -> "fake-dpop" },
    clientAssertionProvider = { "fake-client-assertion" },
    params = AccessTokenParams(
        clientId = "test-client-id",
        scopes = listOf("openid"),
        audience = "https://zeta-kind.local/pep/",
        productId = "",
        productVersion = "",
        expiration = 30,
        platformProductId = PlatformProductId.LinuxProductId("", "", "", ""),
    ),
)

private fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun oidcProvider(
    idpIss: String = "https://sekidp.example.org",
    requestUri: String = "http://localhost:8090/cb/demo",
): OidcTokenProvider = OidcTokenProvider(
    OidcConfig(
        idpIss = idpIss,
        idpAlias = "zeta-sekidp-oidc",
        requestUri = requestUri,
        authenticationCallback = null,
        otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = error("not used")
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission = error("not used")
        },
    ),
)
