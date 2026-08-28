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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AuthenticationApi
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import de.gematik.zeta.sdk.crypto.hashWithSha256
import io.ktor.http.Parameters
import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.measureTimedValue

data class TokenIssuanceRequest(
    val tokenEndpoint: String,
    val nonce: ByteArray,
    val dpopProvider: suspend (htu: String, tokenToHash: String?) -> String,
    val clientAssertionProvider: suspend () -> String,
    val params: AccessTokenParams,
)

/**
 * Interactive OIDC token issuance: PKCE + PAR, browser-based authorization via the
 * [AuthenticationCallback], authorization-code exchange at the token endpoint, and — for
 * mobile clients on first use — the email-binding sub-flow (`zeta:email-verify`).
 *
 * Endpoints are resolved lazily from the discovered issuer via the `resolve*Endpoint` lambdas,
 * so discovery must have run before [issue] is called.
 */
class OidcTokenIssuance(
    private val authApi: AuthenticationApi,
    private val authStorage: AuthenticationStorage,
    private val authenticationCallback: AuthenticationCallback?,
    private val resolveParEndpoint: suspend () -> String,
    private val resolveBindEmailEndpoint: suspend () -> String,
    private val resolveResendEmailEndpoint: suspend () -> String,
    private val resolveVerifyEmailEndpoint: suspend () -> String,
    private val clock: () -> Long,
) {
    companion object {
        private const val GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code"
        private const val GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
        private const val CLIENT_ASSERTION_TYPE_JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
        private const val SUBJECT_TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token"
        private const val REQUESTED_TOKEN_TYPE_REFRESH_TOKEN = "urn:ietf:params:oauth:token-type:refresh_token"
        const val SCOPE_EMAIL_VERIFY = "zeta:email-verify"
        const val BINDING_MODE_COLLECT_EMAIL = "collect_email"
        private const val CHALLENGE_TYPE_EMAIL_OTP = "email_otp"
        private const val BIND_STATUS_BOUND = "bound"
    }

    /**
     * Runs the authorization-code flow: PAR -> [authorize] (user logs in via browser) ->
     * token request with DPoP and client assertion. Returns [TokenIssuanceResult.Issued] with
     * the persisted access token, or [TokenIssuanceResult.EmailBindingRequired] when the token
     * response scope contains `zeta:email-verify` — complete via [completeEmailBinding].
     */
    suspend fun issue(
        request: TokenIssuanceRequest,
        provider: OidcTokenProvider,
    ): TokenIssuanceResult {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()
        val requestUri = postPar(
            provider = provider,
            codeChallenge = challenge,
            state = state,
            clientAssertion = request.clientAssertionProvider(),
            scope = request.params.scopes.joinToString(" "),
        )

        val code = authorize(
            clientId = request.params.clientId,
            requestUri = requestUri,
            expectedState = state,
        )

        val tokenRequest = OidcTokenRequest(
            grantType = GRANT_TYPE_AUTHORIZATION_CODE,
            clientId = request.params.clientId,
            requestedTokenType = REQUESTED_TOKEN_TYPE_REFRESH_TOKEN,
            scope = request.params.scopes.joinToString(" "),
            appRedirectUri = provider.config.requestUriApp,
            nonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(request.nonce),
            code = code,
            codeVerifier = verifier,
            clientAssertionType = CLIENT_ASSERTION_TYPE_JWT_BEARER,
            clientAssertion = request.clientAssertionProvider(),
            audience = request.params.audience,
        )

        val (resp, httpTime) = measureTimedValue {
            authApi.requestOidcToken(request.tokenEndpoint, tokenRequest, request.dpopProvider(request.tokenEndpoint, null))
        }
        Log.d { "[AUTH-TIMING] OidcTokenIssuance requestOidcToken=$httpTime" }

        if (isEmailVerificationPending(resp.scope)) {
            return TokenIssuanceResult.EmailBindingRequired(
                bindingToken = resp.accessToken,
                scope = resp.scope,
                emailHint = resp.emailHint,
                bindingMode = resp.bindingMode,
            )
        }

        val refreshToken = resp.refreshToken
            ?: error("Full OIDC token response missing refresh_token (scope=${resp.scope})")

        authStorage.saveAccessTokens(resp.accessToken, refreshToken, clock() + resp.expiresIn)
        return TokenIssuanceResult.Issued(resp.accessToken)
    }

    suspend fun postPar(
        provider: OidcTokenProvider,
        codeChallenge: String,
        state: String,
        clientAssertion: String,
        scope: String,
    ): String {
        val parameters = Parameters.build {
            append("response_type", "code")
            append("redirect_uri", provider.config.requestUriApp)
            append("oidc_redirect_uri", provider.config.requestUriOidc)
            append("code_challenge", codeChallenge)
            append("code_challenge_method", "S256")
            append("scope", scope)
            append("state", state)
            append("idp_iss", provider.config.idpIss)
            append("client_assertion_type", CLIENT_ASSERTION_TYPE_JWT_BEARER)
            append("client_assertion", clientAssertion)
        }
        return authApi.requestPar(resolveParEndpoint(), parameters).requestUri
    }

    internal suspend fun authorize(
        clientId: String,
        requestUri: String,
        expectedState: String,
    ): String {
        requireNotNull(authenticationCallback) { "authenticationCallback has to be setup" }
        val authInfo = authenticationCallback.authenticationCb(
            clientId = clientId,
            requestUri = requestUri,
        )

        val finalRedirectUrl = authInfo.finalUrl
            ?: error("No redirect returned from authenticationCallback")
        val redirectUrl = Url(finalRedirectUrl)
        val returnedState = redirectUrl.parameters["state"]
        val code = redirectUrl.parameters["code"]
            ?: run {
                val error = redirectUrl.parameters["error"]
                val errorDescription = redirectUrl.parameters["error_description"]
                error("authorization failed: $error - $errorDescription")
            }

        require(returnedState == expectedState) {
            "state mismatch: expected $expectedState but got $returnedState"
        }

        return code
    }

    suspend fun exchangeEmailBindingToken(
        bindingToken: String,
        request: TokenIssuanceRequest,
    ): String {
        val exchangeRequest = OidcTokenRequest(
            grantType = GRANT_TYPE_TOKEN_EXCHANGE,
            clientId = request.params.clientId,
            requestedTokenType = REQUESTED_TOKEN_TYPE_REFRESH_TOKEN,
            clientAssertionType = CLIENT_ASSERTION_TYPE_JWT_BEARER,
            clientAssertion = request.clientAssertionProvider(),
            subjectToken = bindingToken,
            subjectTokenType = SUBJECT_TOKEN_TYPE_ACCESS_TOKEN,
        )

        val resp = authApi.requestOidcToken(request.tokenEndpoint, exchangeRequest, request.dpopProvider(request.tokenEndpoint, null))
        val refreshToken = resp.refreshToken ?: ""

        authStorage.saveAccessTokens(resp.accessToken, refreshToken, clock() + resp.expiresIn)
        return resp.accessToken
    }

    suspend fun requestEmailBinding(
        endpoint: String,
        bindingToken: String,
        dpop: String,
        email: String? = null,
    ): BindEmailResponse = authApi.postBindEmail(
        endpoint = endpoint,
        accessToken = bindingToken,
        dpop = dpop,
        body = BindEmailRequest(email = email),
    )

    suspend fun requestEmailResend(
        endpoint: String,
        bindingToken: String,
        dpop: String,
    ): BindEmailResponse = authApi.postResendOtp(
        endpoint = endpoint,
        accessToken = bindingToken,
        dpop = dpop,
    )

    suspend fun verifyEmailBindingOtp(
        endpoint: String,
        bindingToken: String,
        dpop: String,
        otp: String,
    ): VerifyOtpResponse = authApi.postVerifyOtp(
        endpoint = endpoint,
        accessToken = bindingToken,
        dpop = dpop,
        body = OtpVerifyRequest(otp = otp),
    )

    /**
     * Completes the email binding started by [issue]: collects the email via
     * [OtpCallback.awaitEmail] when `binding_mode = collect_email`, then loops on
     * [OtpCallback.awaitOtp] (verify or resend) until the server reports `status = bound`,
     * and finally exchanges the binding token for the real access + refresh token
     * via [exchangeEmailBindingToken]. Returns the access token.
     */
    suspend fun completeEmailBinding(
        pending: TokenIssuanceResult.EmailBindingRequired,
        request: TokenIssuanceRequest,
        otpCallback: OtpCallback,
    ): String {
        val bindEmailEndpoint = resolveBindEmailEndpoint()
        val resendEndpoint = resolveResendEmailEndpoint()
        val verifyEndpoint = resolveVerifyEmailEndpoint()

        var emailHint = pending.emailHint

        if (requiresEmailCollection(pending.bindingMode)) {
            val email = otpCallback.awaitEmail()
            val dpop = request.dpopProvider(bindEmailEndpoint, pending.bindingToken)
            val bindResult = requestEmailBinding(bindEmailEndpoint, pending.bindingToken, dpop, email)
            check(bindResult.challengeType == CHALLENGE_TYPE_EMAIL_OTP) { "Unexpected challenge_type: ${bindResult.challengeType}" }
            emailHint = bindResult.emailHint
        }

        var rejected = false
        while (true) {
            when (val submission = otpCallback.awaitOtp(emailHint, rejected)) {
                is OtpSubmission.Resend -> {
                    val dpop = request.dpopProvider(resendEndpoint, pending.bindingToken)
                    val resendResult = requestEmailResend(resendEndpoint, pending.bindingToken, dpop)
                    emailHint = resendResult.emailHint
                    rejected = false
                }
                is OtpSubmission.Otp -> {
                    try {
                        val dpop = request.dpopProvider(verifyEndpoint, pending.bindingToken)
                        val verifyResult = verifyEmailBindingOtp(verifyEndpoint, pending.bindingToken, dpop, submission.code)
                        check(verifyResult.status == BIND_STATUS_BOUND) { "Unexpected status after OTP verify: ${verifyResult.status}" }
                        return exchangeEmailBindingToken(pending.bindingToken, request)
                    } catch (_: RecoverableAuthenticationException) {
                        rejected = true
                    }
                }
            }
        }
    }

    @Serializable
    data class OidcTokenRequest(
        @SerialName("grant_type") val grantType: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("requested_token_type") val requestedTokenType: String,
        @SerialName("client_assertion_type") val clientAssertionType: String,
        @SerialName("client_assertion") val clientAssertion: String,
        @SerialName("scope") val scope: String? = null,
        @SerialName("redirect_uri") val appRedirectUri: String? = null, // per spec: app_redirect_uri
        @SerialName("nonce") val nonce: String? = null,
        @SerialName("code") val code: String? = null,
        @SerialName("code_verifier") val codeVerifier: String? = null,
        @SerialName("audience") val audience: String? = null,
        @SerialName("subject_token") val subjectToken: String? = null,
        @SerialName("subject_token_type") val subjectTokenType: String? = null,
    ) {
        fun toParameters(): Parameters = Parameters.build {
            append("grant_type", grantType)
            append("client_id", clientId)
            append("requested_token_type", requestedTokenType)
            append("client_assertion_type", clientAssertionType)
            append("client_assertion", clientAssertion)
            scope?.let { append("scope", it) }
            appRedirectUri?.let { append("redirect_uri", it) } // per spec: app_redirect_uri
            nonce?.let { append("nonce", it) }
            code?.let { append("code", it) }
            codeVerifier?.let { append("code_verifier", it) }
            audience?.let { append("audience", it) }
            subjectToken?.let { append("subject_token", it) }
            subjectTokenType?.let { append("subject_token_type", it) }
        }
    }
}

@Serializable
data class OidcErrorResponse(
    @SerialName("error") val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class ParResponse(
    @SerialName("request_uri") val requestUri: String,
    @SerialName("expires_in") val expiresIn: Int,
)

private fun generateCodeVerifier(): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(Random.nextBytes(32))

private fun generateCodeChallenge(verifier: String): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(hashWithSha256(verifier.encodeToByteArray()))

private fun generateState(): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(Random.nextBytes(16))

fun interface AuthenticationCallback {
    suspend fun authenticationCb(clientId: String, requestUri: String): AuthInfo
    data class AuthInfo(val finalUrl: String? = null)
}

sealed class TokenIssuanceResult {
    data class Issued(val accessToken: String) : TokenIssuanceResult()
    data class EmailBindingRequired(
        val bindingToken: String,
        val scope: String,
        val emailHint: String?,
        val bindingMode: String?,
    ) : TokenIssuanceResult()
}

private fun scopes(scope: String?): List<String> = scope?.split(" ").orEmpty()

private fun isEmailVerificationPending(scope: String?): Boolean =
    OidcTokenIssuance.SCOPE_EMAIL_VERIFY in scopes(scope)

private fun requiresEmailCollection(bindingMode: String?): Boolean =
    bindingMode == OidcTokenIssuance.BINDING_MODE_COLLECT_EMAIL

@Serializable
data class OidcAccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_expires_in") val refreshExpires: Int? = null,
    @SerialName("token_type") val tokenType: String,
    @SerialName("scope") val scope: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("email_hint") val emailHint: String? = null,
    @SerialName("binding_mode") val bindingMode: String? = null,
)

@Serializable
data class BindEmailRequest(val email: String? = null)

@Serializable
data class OtpVerifyRequest(val otp: String)
