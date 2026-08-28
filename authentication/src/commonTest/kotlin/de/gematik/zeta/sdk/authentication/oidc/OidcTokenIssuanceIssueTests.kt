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
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val PAR_ENDPOINT = "https://zeta-kind.local/protocol/openid-connect/ext/par/request"
private const val TOKEN_ENDPOINT = "https://zeta-kind.local/protocol/openid-connect/token"
private const val APP_REDIRECT_URI = "http://localhost:8090/cb/demo/app"

private fun createClient(engine: MockEngine): ZetaHttpClient = ZetaHttpClient(HttpClient(engine))

private fun newAuthStorage(): AuthenticationStorage =
    AuthenticationStorageImpl(InMemoryStorage(), resourceScope = ResourceScope("", emptyList()))

private fun oidcProvider(): OidcTokenProvider = OidcTokenProvider(
    OidcConfig(
        idpIss = "https://sekidp.example.org",
        idpAlias = "zeta-sekidp-oidc",
        requestUri = "http://localhost:8090/cb/demo",
        authenticationCallback = null,
        otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = error("not used")
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission = error("not used")
        },
    ),
)

private fun fakeRequest(): TokenIssuanceRequest = TokenIssuanceRequest(
    tokenEndpoint = TOKEN_ENDPOINT,
    nonce = byteArrayOf(1, 2, 3, 4),
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

private fun issueFlowEngine(
    tokenResponseBody: String,
    parRequestUri: String = "urn:ietf:params:oauth:request_uri:abc123",
    onTokenRequest: (Map<String, String>) -> Unit = {},
): Pair<MockEngine, () -> String> {
    var capturedState = ""
    val engine = MockEngine { request ->
        when (request.url.toString()) {
            PAR_ENDPOINT -> {
                val body = request.body.toByteArray().decodeToString()
                capturedState = parseQueryString(body)["state"] ?: ""
                respond(
                    content = """{"request_uri":"$parRequestUri","expires_in":60}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            TOKEN_ENDPOINT -> {
                val body = request.body.toByteArray().decodeToString()
                onTokenRequest(
                    parseQueryString(body).let { params ->
                        params.names().associateWith { name -> params[name] ?: "" }
                    },
                )
                respond(
                    content = tokenResponseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            else -> error("Unexpected request to ${request.url}")
        }
    }
    return engine to { capturedState }
}

private fun issuanceWithCallback(
    engine: MockEngine,
    getCapturedState: () -> String,
    storage: AuthenticationStorage = newAuthStorage(),
): OidcTokenIssuance {
    val callback = AuthenticationCallback { _, _ ->
        AuthenticationCallback.AuthInfo(
            finalUrl = "$APP_REDIRECT_URI?code=auth-code-123&state=${getCapturedState()}",
        )
    }
    return OidcTokenIssuance(
        authApi = AuthenticationApiImpl(createClient(engine)),
        authStorage = storage,
        authenticationCallback = callback,
        resolveParEndpoint = { PAR_ENDPOINT },
        resolveBindEmailEndpoint = { error("not used") },
        resolveResendEmailEndpoint = { error("not used") },
        resolveVerifyEmailEndpoint = { error("not used") },
        clock = { 1000L },
    )
}

class OidcTokenIssuanceIssueTest {

    @Test
    fun issue_returnsIssued_andSavesTokens_whenEmailVerificationNotPending() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh-token"}""",
        )
        val storage = newAuthStorage()
        val sut = issuanceWithCallback(engine, capturedState, storage)

        // Act
        val result = sut.issue(fakeRequest(), oidcProvider())

        // Assert
        assertEquals(TokenIssuanceResult.Issued("final-access-token"), result)
        assertEquals("final-access-token", storage.getAccessToken())
        assertEquals("final-refresh-token", storage.getRefreshToken())
    }

    @Test
    fun issue_returnsEmailBindingRequired_whenScopeContainsEmailVerify() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"openid zeta:email-verify","email_hint":"t*@e*.com","binding_mode":"verify_otp"}""",
        )
        val sut = issuanceWithCallback(engine, capturedState)

        // Act
        val result = sut.issue(fakeRequest(), oidcProvider())

        // Assert
        val expected = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "openid zeta:email-verify",
            emailHint = "t*@e*.com",
            bindingMode = "verify_otp",
        )
        assertEquals(expected, result)
    }

    @Test
    fun issue_doesNotSaveTokens_whenEmailVerificationPending() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-verify"}""",
        )
        val storage = newAuthStorage()
        val sut = issuanceWithCallback(engine, capturedState, storage)

        // Act
        sut.issue(fakeRequest(), oidcProvider())

        // Assert
        assertNull(storage.getAccessToken())
    }

    @Test
    fun issue_throws_whenRefreshTokenMissing_andEmailVerificationNotPending() = runTest {
        // Arrange: no email-verify scope, so a refresh_token is required but absent here
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"token_type":"DPoP","scope":"openid"}""",
        )
        val sut = issuanceWithCallback(engine, capturedState)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), oidcProvider())
        }
    }

    @Test
    fun issue_sendsExpectedTokenRequestParameters() = runTest {
        // Arrange
        var capturedParams: Map<String, String>? = null
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh-token"}""",
            onTokenRequest = { capturedParams = it },
        )
        val sut = issuanceWithCallback(engine, capturedState)
        val request = fakeRequest()

        // Act
        sut.issue(request, oidcProvider())

        // Assert
        val params = requireNotNull(capturedParams)
        assertEquals("authorization_code", params["grant_type"])
        assertEquals("test-client-id", params["client_id"])
        assertEquals("openid", params["scope"])
        assertEquals(APP_REDIRECT_URI, params["redirect_uri"])
        assertEquals("auth-code-123", params["code"])
        assertEquals("https://zeta-kind.local/pep/", params["audience"])
        assertEquals(
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(request.nonce),
            params["nonce"],
        )
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", params["client_assertion_type"])
        assertEquals("fake-client-assertion", params["client_assertion"])
    }

    @Test
    fun issue_throws_whenAuthenticationCallbackReturnsMismatchedState() = runTest {
        // Arrange: callback returns a state that does NOT match what was sent to PAR
        val (engine, _) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"x","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"y"}""",
        )
        val mismatchedCallback = AuthenticationCallback { _, _ ->
            AuthenticationCallback.AuthInfo(finalUrl = "$APP_REDIRECT_URI?code=auth-code-123&state=wrong-state")
        }
        val sut = OidcTokenIssuance(
            authApi = AuthenticationApiImpl(createClient(engine)),
            authStorage = newAuthStorage(),
            authenticationCallback = mismatchedCallback,
            resolveParEndpoint = { PAR_ENDPOINT },
            resolveBindEmailEndpoint = { error("not used") },
            resolveResendEmailEndpoint = { error("not used") },
            resolveVerifyEmailEndpoint = { error("not used") },
            clock = { 1000L },
        )

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            sut.issue(fakeRequest(), oidcProvider())
        }
    }
}
