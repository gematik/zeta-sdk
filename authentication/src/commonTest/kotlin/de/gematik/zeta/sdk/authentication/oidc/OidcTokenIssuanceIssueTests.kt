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
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import secureRandom
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val PAR_ENDPOINT = "https://zeta-kind.local/protocol/openid-connect/ext/par/request"
private const val TOKEN_ENDPOINT = "https://zeta-kind.local/protocol/openid-connect/token"
private const val APP_REDIRECT_URI = "http://localhost:8090/cb/demo/app"
private const val OIDC_REDIRECT_URI = "http://localhost:8090/cb/demo/oidc"
private const val AUTHORIZATION_ENDPOINT = "https://zeta-kind.local/protocol/openid-connect/auth"
private const val BROKER_ENDPOINT = "https://zeta-kind.local/broker/zeta-sekidp-oidc/endpoint"

private fun createClient(engine: MockEngine): ZetaHttpClient = ZetaHttpClient(HttpClient(engine))

private fun newAuthStorage(): AuthenticationStorage =
    AuthenticationStorageImpl(InMemoryStorage(), resourceScope = ResourceScope("", emptyList()))

private val unusedOtpCallback = object : OtpCallback {
    override suspend fun awaitEmail(): String = error("not used")
    override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission = error("not used")
}

private fun oidcProvider(launcher: AuthorizationLauncher): OidcTokenProvider = OidcTokenProvider(
    OidcConfig(
        redirectUri = APP_REDIRECT_URI,
        oidcRedirectUri = OIDC_REDIRECT_URI,
        idpIss = "https://sekidp.example.org",
        idpAlias = "zeta-sekidp-oidc",
        authorizationLauncher = launcher,
        otpCallback = unusedOtpCallback,
    ),
).apply {
    resolveAuthorizationEndpoint = { AUTHORIZATION_ENDPOINT }
    resolveBrokerEndpoint = { BROKER_ENDPOINT }
}

private fun oidcProviderEchoingState(capturedState: () -> String): OidcTokenProvider =
    oidcProvider { "$APP_REDIRECT_URI?code=auth-code-123&state=${capturedState()}" }

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

private fun Parameters.toSingleValueMap(): Map<String, String> =
    names().associateWith { name -> this[name] ?: "" }

private fun issueFlowEngine(
    tokenResponseBody: String,
    parRequestUri: String = "urn:ietf:params:oauth:request_uri:abc123",
    onParRequest: (Map<String, String>) -> Unit = {},
    onTokenRequest: (Map<String, String>) -> Unit = {},
): Pair<MockEngine, () -> String> {
    var capturedState = ""
    val engine = MockEngine { request ->
        when (request.url.toString()) {
            PAR_ENDPOINT -> {
                val params = parseQueryString(request.body.toByteArray().decodeToString())
                capturedState = params["state"] ?: ""
                onParRequest(params.toSingleValueMap())
                respond(
                    content = """{"request_uri":"$parRequestUri","expires_in":60}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            TOKEN_ENDPOINT -> {
                val params = parseQueryString(request.body.toByteArray().decodeToString())
                onTokenRequest(params.toSingleValueMap())
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

private fun issuance(
    engine: MockEngine,
    storage: AuthenticationStorage = newAuthStorage(),
    randomBytes: (ByteArray) -> ByteArray = ::secureRandom,
): OidcTokenIssuance = OidcTokenIssuance(
    authApi = AuthenticationApiImpl(createClient(engine), clock = SystemZetaClock),
    authStorage = storage,
    resolveParEndpoint = { PAR_ENDPOINT },
    resolveBindEmailEndpoint = { error("not used") },
    resolveResendEmailEndpoint = { error("not used") },
    resolveVerifyEmailEndpoint = { error("not used") },
    clock = { 1000L },
    randomBytes = randomBytes,
)

class OidcTokenIssuanceIssueTest {
    private val issuedBody =
        """{"access_token":"a","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"r"}"""

    private val base64UrlNoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun issue_returnsIssued_andSavesTokens_whenEmailVerificationNotPending() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh-token"}""",
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        val result = sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        assertEquals(TokenIssuanceResult.Issued("final-access-token"), result)
        assertEquals("final-access-token", storage.getAccessToken())
        assertEquals("final-refresh-token", storage.getRefreshToken())
    }

    @Test
    fun issue_returnsEmailBindingRequired_whenCollectEmailWithEmailBindingScope() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"openid zeta:email-binding","binding_mode":"collect_email"}""",
        )
        val sut = issuance(engine)

        // Act
        val result = sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        val expected = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "openid zeta:email-binding",
            emailHint = null,
            bindingMode = "collect_email",
        )
        assertEquals(expected, result)
    }

    @Test
    fun issue_doesNotSaveTokens_whenEmailBindingPending() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-binding","binding_mode":"collect_email"}""",
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        assertNull(storage.getAccessToken())
    }

    @Test
    fun issue_returnsEmailBindingRequired_whenVerifyOtpWithEmailVerifyScope() = runTest {
        // Arrange: known identity, the OTP was already sent to the stored address
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-verify","email_hint":"t*@e*.com","binding_mode":"verify_otp"}""",
        )
        val sut = issuance(engine)

        // Act
        val result = sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        val expected = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "zeta:email-verify",
            emailHint = "t*@e*.com",
            bindingMode = "verify_otp",
        )
        assertEquals(expected, result)
    }

    @Test
    fun issue_throws_whenCollectEmailWithoutEmailBindingScope() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-verify","binding_mode":"collect_email"}""",
        )
        val sut = issuance(engine)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))
        }
    }

    @Test
    fun issue_throws_whenVerifyOtpWithoutEmailVerifyScope() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-binding","binding_mode":"verify_otp"}""",
        )
        val sut = issuance(engine)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))
        }
    }

    @Test
    fun issue_throws_whenBindingModeUnknown() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-binding","binding_mode":"something_else"}""",
        )
        val sut = issuance(engine)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))
        }
    }

    @Test
    fun issue_throws_whenEmailBindingScopeWithoutBindingMode() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-binding zeta:email-verify","refresh_token":"rt"}""",
        )
        val sut = issuance(engine)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))
        }
    }

    @Test
    fun issue_returnsEmailBindingRequired_evenIfPendingResponseHasRefreshToken() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"binding-token","expires_in":300,"token_type":"DPoP","scope":"zeta:email-binding","refresh_token":"unexpected","binding_mode":"collect_email"}""",
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        val result = sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        assertEquals("binding-token", (result as TokenIssuanceResult.EmailBindingRequired).bindingToken)
        assertNull(storage.getAccessToken())
    }

    @Test
    fun issue_throws_whenRefreshTokenMissing_andEmailBindingNotPending() = runTest {
        // Arrange: no email-binding scope, so a refresh_token is required but absent here
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"token_type":"DPoP","scope":"openid"}""",
        )
        val sut = issuance(engine)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))
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
        val sut = issuance(engine)
        val request = fakeRequest()

        // Act
        sut.issue(request, oidcProviderEchoingState(capturedState))

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
    fun issue_throws_whenLauncherReturnsMismatchedState() = runTest {
        // Arrange: launcher returns a state that does NOT match what was sent to PAR
        val (engine, _) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"x","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"y"}""",
        )
        val sut = issuance(engine)
        val provider = oidcProvider { "$APP_REDIRECT_URI?code=auth-code-123&state=wrong-state" }

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), provider)
        }
    }

    @Test
    fun issue_doesNotRequestToken_whenLoginFails() = runTest {
        // Arrange: the user cancels the login in the browser
        var tokenRequested = false
        val (engine, _) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"x","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"y"}""",
            onTokenRequest = { tokenRequested = true },
        )
        val sut = issuance(engine)
        val provider = oidcProvider { throw AuthorizationFailedException("access_denied", "User cancelled the login") }

        // Act & Assert
        assertFailsWith<AuthorizationFailedException> {
            sut.issue(fakeRequest(), provider)
        }
        assertFalse(tokenRequested)
    }

    @Test
    fun issue_savesAccessTokenExpiration_fromExpiresIn() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh-token"}""",
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        assertEquals("1300", storage.getAccessTokenExpiration())
    }

    @Test
    fun issue_savesRefreshTokenExpiration_whenRefreshExpiresInPresent() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"refresh_expires_in":600,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh-token"}""",
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        assertEquals("1600", storage.getRefreshTokenExpiration())
    }

    @Test
    fun issue_storesUnknownRefreshExpiration_whenRefreshExpiresInAbsent() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh-token"}""",
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        assertNull(storage.getRefreshTokenExpiration())
    }

    @Test
    fun issue_storesUnknownRefreshExpiration_whenRefreshExpiresInIsZero() = runTest {
        // Arrange
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = """{"access_token":"final-access-token","expires_in":300,"refresh_expires_in":0,"token_type":"DPoP","scope":"openid","refresh_token":"final-refresh-token"}""",
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage)

        // Act
        sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        // Assert
        assertNull(storage.getRefreshTokenExpiration())
    }

    @Test
    fun issue_generatesCodeVerifierAndState_fromRandomSource() = runTest {
        val requestedSizes = mutableListOf<Int>()
        val fakeRandom: (ByteArray) -> ByteArray = { buffer ->
            requestedSizes += buffer.size
            buffer.apply { fill(requestedSizes.size.toByte()) }
        }
        var par: Map<String, String>? = null
        var token: Map<String, String>? = null
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = issuedBody,
            onParRequest = { par = it },
            onTokenRequest = { token = it },
        )
        val sut = issuance(engine, randomBytes = fakeRandom)

        // Act
        sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))

        assertEquals(listOf(32, 16), requestedSizes)
        assertEquals(base64UrlNoPadding.encode(ByteArray(32) { 1 }), token?.get("code_verifier"))
        assertEquals(base64UrlNoPadding.encode(ByteArray(16) { 2 }), par?.get("state"))
    }

    @Test
    fun issue_failsWithoutNetworkCall_whenRandomSourceFails() = runTest {
        // Arrange
        var parRequested = false
        var tokenRequested = false
        val (engine, capturedState) = issueFlowEngine(
            tokenResponseBody = issuedBody,
            onParRequest = { parRequested = true },
            onTokenRequest = { tokenRequested = true },
        )
        val storage = newAuthStorage()
        val sut = issuance(engine, storage, randomBytes = { throw IllegalStateException("RNG unavailable") })

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            sut.issue(fakeRequest(), oidcProviderEchoingState(capturedState))
        }
        assertFalse(parRequested)
        assertFalse(tokenRequested)
        assertNull(storage.getAccessToken())
    }
}
