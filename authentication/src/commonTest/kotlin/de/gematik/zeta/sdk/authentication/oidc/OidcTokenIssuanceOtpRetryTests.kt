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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RETRY_BIND_ENDPOINT = "https://zeta-kind.local/zeta/identity/bind-email"
private const val RETRY_RESEND_ENDPOINT = "https://zeta-kind.local/zeta/identity/bind-email/resend"
private const val RETRY_VERIFY_ENDPOINT = "https://zeta-kind.local/zeta/identity/bind-email/verify"
private const val RETRY_TOKEN_ENDPOINT = "https://zeta-kind.local/token"

private fun retryAuthStorage(): AuthenticationStorage =
    AuthenticationStorageImpl(InMemoryStorage(), resourceScope = ResourceScope("", emptyList()))

private fun retryIssuance(
    engine: MockEngine,
    storage: AuthenticationStorage = retryAuthStorage(),
): OidcTokenIssuance = OidcTokenIssuance(
    authApi = AuthenticationApiImpl(ZetaHttpClient(HttpClient(engine)), SystemZetaClock),
    authStorage = storage,
    authenticationCallback = null,
    resolveParEndpoint = { error("not used") },
    resolveBindEmailEndpoint = { RETRY_BIND_ENDPOINT },
    resolveResendEmailEndpoint = { RETRY_RESEND_ENDPOINT },
    resolveVerifyEmailEndpoint = { RETRY_VERIFY_ENDPOINT },
    clock = { 1000L },
)

private fun retryRequest(): TokenIssuanceRequest = TokenIssuanceRequest(
    tokenEndpoint = RETRY_TOKEN_ENDPOINT,
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

class OidcTokenIssuanceOtpRetryTests {

    @Test
    fun completeEmailBinding_marksSubmissionRejected_andRetries_whenVerifyIsUnauthorized() = runTest {
        var verifyCallCount = 0
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                RETRY_VERIFY_ENDPOINT -> {
                    verifyCallCount++
                    if (verifyCallCount == 1) {
                        respond(
                            content = "invalid otp",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    } else {
                        respond(
                            content = """{"status":"bound"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
                RETRY_TOKEN_ENDPOINT -> respond(
                    content = """{"access_token":"exchanged-token","expires_in":300,"token_type":"DPoP","scope":"openid","refresh_token":"r"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request to ${request.url}")
            }
        }
        val rejectedFlags = mutableListOf<Boolean>()
        val otpCallback = object : OtpCallback {
            override suspend fun awaitEmail(): String = error("should not be called")
            override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission {
                rejectedFlags.add(rejected)
                return OtpSubmission.Otp(if (rejectedFlags.size == 1) "000000" else "123456")
            }
        }
        val pending = TokenIssuanceResult.EmailBindingRequired(
            bindingToken = "binding-token",
            scope = "zeta:email-verify",
            emailHint = "t*@e*.com",
            bindingMode = "verify_otp",
        )

        // Act
        val token = retryIssuance(engine).completeEmailBinding(pending, retryRequest(), otpCallback)

        // Assert
        assertEquals("exchanged-token", token)
        assertEquals(listOf(false, true), rejectedFlags)
        assertEquals(2, verifyCallCount)
    }

    @Test
    fun requestEmailBinding_returnsParsedChallenge_whenCalledWithoutEmailArgument() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"challenge_type":"email_otp","email_hint":"t*@e*.com"}""",
                status = HttpStatusCode.Accepted,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        // Act
        val response = retryIssuance(engine).requestEmailBinding(
            endpoint = RETRY_BIND_ENDPOINT,
            bindingToken = "binding-token",
            dpop = "dpop",
        )

        // Assert
        assertEquals("email_otp", response.challengeType)
        assertEquals("t*@e*.com", response.emailHint)
    }

    @Test
    fun exchangeEmailBindingToken_storesEmptyRefreshToken_whenResponseHasNoRefreshToken() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"access_token":"exchanged","expires_in":300,"token_type":"DPoP","scope":""}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val storage = retryAuthStorage()

        // Act
        val token = retryIssuance(engine, storage).exchangeEmailBindingToken("binding-token", retryRequest())

        // Assert
        assertEquals("exchanged", token)
        assertEquals("exchanged", storage.getAccessToken())
        assertTrue(storage.getRefreshToken().isNullOrEmpty())
    }
}
