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
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AuthenticationApi
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import de.gematik.zeta.sdk.authentication.computeTokenExpirations
import de.gematik.zeta.sdk.authentication.identity.models.ClientManagementScopes
import de.gematik.zeta.sdk.crypto.hashWithSha256
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import secureRandom
import kotlin.io.encoding.Base64
import kotlin.time.measureTimedValue

data class TokenIssuanceRequest(
    val tokenEndpoint: String,
    val nonce: ByteArray,
    val dpopProvider: suspend (htu: String, tokenToHash: String?) -> String,
    val clientAssertionProvider: suspend () -> String,
    val params: AccessTokenParams,
)

/**
 * OIDC token issuance per Abb-ZETA-OIDC-Token-Bezug-mit-Identitaetsbindung: PAR, browser
 * authorization, authorization-code exchange, the email-binding sub-flow while the registration
 * is `pending_user_binding` (scope `zeta:email-binding`) and the RFC 8693 token exchange.
 */
class OidcTokenIssuance(
    private val authApi: AuthenticationApi,
    private val authStorage: AuthenticationStorage,
    private val resolveParEndpoint: suspend () -> String,
    private val resolveBindEmailEndpoint: suspend () -> String,
    private val resolveResendEmailEndpoint: suspend () -> String,
    private val resolveVerifyEmailEndpoint: suspend () -> String,
    private val clock: () -> Long,
    private val attestationState: AttestationStateStorage? = null,
    private val randomBytes: (ByteArray) -> ByteArray = ::secureRandom,
) {
    companion object {
        private const val GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code"
        private const val GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
        private const val CLIENT_ASSERTION_TYPE_JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
        private const val SUBJECT_TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token"
        private const val REQUESTED_TOKEN_TYPE_REFRESH_TOKEN = "urn:ietf:params:oauth:token-type:refresh_token"
        const val BINDING_MODE_COLLECT_EMAIL = "collect_email"
        const val BINDING_MODE_VERIFY_OTP = "verify_otp"
        private const val CHALLENGE_TYPE_EMAIL_OTP = "email_otp"
        private const val BIND_STATUS_BOUND = "bound"
    }

    private suspend fun storeAttestationToken(token: String?) {
        if (token == null) return
        val state = attestationState
        if (state == null) {
            Log.d { "ZETA attestation token received but no attestation state configured" }
            return
        }
        state.setZetaAttestationToken(token)
        Log.i { "Stored ZETA attestation token for fast-path re-registration" }
    }

    suspend fun issue(
        request: TokenIssuanceRequest,
        provider: OidcTokenProvider,
    ): TokenIssuanceResult {
        val verifier = generateCodeVerifier(randomBytes)
        val challenge = generateCodeChallenge(verifier)
        val state = generateState(randomBytes)
        val authorizationEndpoint = provider.resolveAuthorizationEndpoint()
        val brokerEndpoint = provider.resolveBrokerEndpoint()

        val requestUri = postPar(
            provider = provider,
            codeChallenge = challenge,
            state = state,
            clientAssertion = request.clientAssertionProvider(),
            scope = request.params.scopes.joinToString(" "),
        )

        val code = authorize(
            provider = provider,
            clientId = request.params.clientId,
            requestUri = requestUri,
            expectedState = state,
            authorizationEndpoint = authorizationEndpoint,
            brokerEndpoint = brokerEndpoint,
        )

        // Abb-ZETA-OIDC-Token-Bezug (09) POST /token grant_type=authorization_code
        val tokenRequest = OidcTokenRequest(
            grantType = GRANT_TYPE_AUTHORIZATION_CODE,
            clientId = request.params.clientId,
            requestedTokenType = REQUESTED_TOKEN_TYPE_REFRESH_TOKEN,
            scope = request.params.scopes.joinToString(" "),
            appRedirectUri = provider.config.redirectUri,
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
        Log.d {
            "[OIDC] token response: scope=${resp.scope} binding_mode=${resp.bindingMode} email_hint=${resp.emailHint} " +
                "refresh_token=${presence(resp.refreshToken)} zeta_attestation_token=${presence(resp.zetaAttestationToken)}"
        }

        // Abb-ZETA-OIDC-Token-Bezug (15) / (24) reduced E-Mail-Binding token, registration is pending_user_binding.
        val bindingMode = resp.bindingMode
        if (bindingMode != null) {
            val requiredScope = requiredScopeFor(bindingMode)
            check(requiredScope in scopes(resp.scope)) {
                "binding_mode=$bindingMode requires scope $requiredScope (scope=${resp.scope})"
            }
            if (resp.refreshToken != null) Log.w { "E-Mail-Binding token response unexpectedly carries a refresh_token; ignoring it" }
            return TokenIssuanceResult.EmailBindingRequired(
                bindingToken = resp.accessToken,
                scope = resp.scope,
                emailHint = resp.emailHint,
                bindingMode = bindingMode,
            )
        }
        check(!hasEmailBindingScope(resp.scope)) { "Token response has an E-Mail-Binding scope but no binding_mode (scope=${resp.scope})" }

        // Abb-ZETA-OIDC-Token-Bezug (33) / (34) full tokens, registration already bound
        val refreshToken = resp.refreshToken
            ?: error("Token response has neither binding_mode nor a refresh_token (scope=${resp.scope})")

        storeAttestationToken(resp.zetaAttestationToken)
        val (accessExpiresAt, refreshExpiresAt) = computeTokenExpirations(clock(), resp.expiresIn, resp.refreshExpires)

        authStorage.saveAccessTokens(resp.accessToken, refreshToken, accessExpiresAt, refreshExpiresAt)
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
            append("redirect_uri", provider.config.redirectUri)
            append("oidc_redirect_uri", provider.config.oidcRedirectUri)
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

    suspend fun authorize(
        provider: OidcTokenProvider,
        clientId: String,
        requestUri: String,
        expectedState: String,
        authorizationEndpoint: String,
        brokerEndpoint: String,
    ): String {
        val authorizationUrl = URLBuilder(authorizationEndpoint).apply {
            parameters.append("client_id", clientId)
            parameters.append("request_uri", requestUri)
        }.buildString()

        val finalUrl = provider.config.authorizationLauncher.open(
            AuthorizationRequest(
                authorizationUrl = authorizationUrl,
                redirectUri = provider.config.redirectUri,
                oidcRedirectUri = provider.config.oidcRedirectUri,
                brokerEndpoint = brokerEndpoint,
            ),
        )

        val params = Url(finalUrl).parameters
        params["error"]?.let { throw AuthorizationFailedException(it, params["error_description"]) }

        val returnedState = requireNotNull(params["state"]) { "Final callback does not contain 'state'" }
        check(returnedState == expectedState) { "State mismatch in authorization callback" }

        return requireNotNull(params["code"]) { "Final callback does not contain 'code'" }
    }

    suspend fun exchangeEmailBindingToken(
        bindingToken: String,
        request: TokenIssuanceRequest,
    ): String {
        // Abb-ZETA-OIDC-Token-Bezug (30) POST /token grant_type=token-exchange with the E-Mail-Binding token
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
        if (resp.refreshToken == null) Log.w { "Token exchange response missing refresh_token; the next token request will restart the OIDC flow" }
        val refreshToken = resp.refreshToken ?: ""

        // Abb-ZETA-OIDC-Token-Bezug (33) / (34) full tokens
        storeAttestationToken(resp.zetaAttestationToken)
        val (accessExpiresAt, refreshExpiresAt) = computeTokenExpirations(clock(), resp.expiresIn, resp.refreshExpires)

        authStorage.saveAccessTokens(resp.accessToken, refreshToken, accessExpiresAt, refreshExpiresAt)
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

    suspend fun completeEmailBinding(
        pending: TokenIssuanceResult.EmailBindingRequired,
        request: TokenIssuanceRequest,
        otpCallback: OtpCallback,
    ): String {
        val bindEmailEndpoint = resolveBindEmailEndpoint()
        val resendEndpoint = resolveResendEmailEndpoint()
        val verifyEndpoint = resolveVerifyEmailEndpoint()

        var emailHint = pending.emailHint

        if (pending.bindingMode == BINDING_MODE_COLLECT_EMAIL) {
            // Abb-ZETA-OIDC-Token-Bezug (16) (17) ask the user for the email address
            val email = otpCallback.awaitEmail()
            val dpop = request.dpopProvider(bindEmailEndpoint, pending.bindingToken)
            // Abb-ZETA-OIDC-Token-Bezug (18) POST /zeta/identity/bind-email
            val bindResult = requestEmailBinding(bindEmailEndpoint, pending.bindingToken, dpop, email)
            // Abb-ZETA-OIDC-Token-Bezug (21) 202 Accepted {challenge_type: email_otp}
            check(bindResult.challengeType == CHALLENGE_TYPE_EMAIL_OTP) { "Unexpected challenge_type: ${bindResult.challengeType}" }
            emailHint = bindResult.emailHint
        }

        var rejected = false
        while (true) {
            // Abb-ZETA-OIDC-Token-Bezug (25) (26) ask the user for the OTP
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
                        // Abb-ZETA-OIDC-Token-Bezug (27) POST /zeta/identity/bind-email/verify
                        val verifyResult = verifyEmailBindingOtp(verifyEndpoint, pending.bindingToken, dpop, submission.code)
                        // Abb-ZETA-OIDC-Token-Bezug (29) 200 OK {status: bound}
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

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private fun generateCodeChallenge(verifier: String): String =
    base64Url.encode(hashWithSha256(verifier.encodeToByteArray()))
private fun generateCodeVerifier(random: (ByteArray) -> ByteArray): String = base64Url.encode(random(ByteArray(32)))
private fun generateState(random: (ByteArray) -> ByteArray): String = base64Url.encode(random(ByteArray(16)))

fun interface AuthenticationCallback {
    suspend fun authenticationCb(authorizationUrl: String): AuthInfo
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

private fun presence(value: String?): String = if (value != null) "present" else "absent"

private fun requiredScopeFor(bindingMode: String): String = when (bindingMode) {
    OidcTokenIssuance.BINDING_MODE_COLLECT_EMAIL -> ClientManagementScopes.EMAIL_BINDING
    OidcTokenIssuance.BINDING_MODE_VERIFY_OTP -> ClientManagementScopes.EMAIL_VERIFY
    else -> error("Unknown binding_mode: $bindingMode")
}

private fun hasEmailBindingScope(scope: String?): Boolean =
    scopes(scope).any { it == ClientManagementScopes.EMAIL_BINDING || it == ClientManagementScopes.EMAIL_VERIFY }

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
    @SerialName("zeta_attestation_token") val zetaAttestationToken: String? = null,
)

@Serializable
data class BindEmailRequest(val email: String? = null)

@Serializable
data class OtpVerifyRequest(val otp: String)
