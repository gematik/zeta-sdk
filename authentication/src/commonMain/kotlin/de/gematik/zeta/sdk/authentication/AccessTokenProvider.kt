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

import Jwk
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.AttestationApi
import de.gematik.zeta.sdk.attestation.AttestationApiImpl
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.assertion.HardwarePostureBuilder
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.model.DPoPTokenClaims
import de.gematik.zeta.sdk.authentication.model.DPopTokenHeader
import de.gematik.zeta.sdk.authentication.model.TokenType
import de.gematik.zeta.sdk.authentication.oidc.OidcTokenIssuance
import de.gematik.zeta.sdk.authentication.oidc.TokenIssuanceRequest
import de.gematik.zeta.sdk.authentication.oidc.TokenIssuanceResult
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.utils.io.core.toByteArray
import kotlin.io.encoding.Base64

data class AccessTokenParams(
    val clientId: String,
    val productId: String,
    val productVersion: String,
    val expiration: Long,
    val scopes: List<String>,
    val audience: String,
    val platformProductId: PlatformProductId,
)

interface AccessTokenProvider {
    suspend fun getValidToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, dpopKey: String): String
    suspend fun createDpopToken(dpopKey: Jwk, method: String, url: String, nonceBytes: ByteArray? = null, accessTokenHash: String? = null): String
    suspend fun hash(token: String): String
}

@Suppress("FunctionOnlyReturningConstant", "standard:max-line-length")
open class AccessTokenProviderImpl(
    private val resource: String,
    private val authConfig: AuthConfig,
    private val authApi: AuthenticationApi,
    private val authStorage: AuthenticationStorage,
    private val clock: () -> Long,
    private val tpmProvider: TpmProvider,
    private val hardwarePostureBuilder: HardwarePostureBuilder? = null,
    private val attestationState: AttestationStateStorage? = null,
) : AccessTokenProvider {

    open val attestationApi: AttestationApi by lazy {
        AttestationApiImpl(
            tpmProvider = tpmProvider,
            attestationConfig = authConfig.attestation,
            clockEpochSeconds = clock,
            hardwarePostureBuilder = hardwarePostureBuilder,
        )
    }

    private val subjectTokenIssuance by lazy {
        SubjectTokenIssuance(
            authConfig = authConfig,
            authApi = authApi,
            authStorage = authStorage,
            tpmProvider = tpmProvider,
            clock = clock,
            attestationState = attestationState,
        )
    }

    private val oidcTokenIssuance by lazy {
        OidcTokenIssuance(
            authApi = authApi,
            authStorage = authStorage,
            clock = clock,
            resolveParEndpoint = (authConfig.subjectTokenProvider as OidcTokenProvider).resolveParEndpoint,
            resolveBindEmailEndpoint = authConfig.subjectTokenProvider.resolveBindEmailEndpoint,
            resolveVerifyEmailEndpoint = authConfig.subjectTokenProvider.resolveVerifyEmailEndpoint,
            resolveResendEmailEndpoint = authConfig.subjectTokenProvider.resolveResendEmailEndpoint,
            attestationState = attestationState,
        )
    }

    override suspend fun getValidToken(
        tokenEndpoint: String,
        nonceEndpoint: String,
        params: AccessTokenParams,
        dpopKey: String,
    ): String {
        cachedAccessToken()?.let {
            return it
        }

        val token = tryRefresh(tokenEndpoint, nonceEndpoint, params)
            ?: issueNewAccessToken(tokenEndpoint, nonceEndpoint, params, dpopKey)

        return token
    }

    private suspend fun cachedAccessToken(): String? {
        val token = authStorage.getAccessToken() ?: return null
        val secondsLeft = secondsUntil(authStorage.getAccessTokenExpiration()) ?: return null
        return token.takeIf { secondsLeft > SAFETY_MARGIN_SECS }
    }

    private suspend fun usableRefreshToken(): String? {
        val token = authStorage.getRefreshToken()?.takeIf { it.isNotBlank() }
        if (token == null) {
            Log.d { "No refresh token found" }
            return null
        }
        val secondsLeft = secondsUntil(authStorage.getRefreshTokenExpiration())
        if (secondsLeft != null && secondsLeft <= SAFETY_MARGIN_SECS) {
            Log.d { "Refresh token expired, skipping refresh" }
            return null
        }
        return token
    }

    private suspend fun tryRefresh(
        tokenEndpoint: String,
        nonceEndpoint: String,
        params: AccessTokenParams,
    ): String? {
        val refreshToken = usableRefreshToken() ?: return null
        return try {
            refreshToken(tokenEndpoint, nonceEndpoint, params, refreshToken)
        } catch (e: AuthenticationException) {
            Log.w { "Refresh token failed: (${e.message}); requesting a new token" }
            null
        }
    }

    private fun secondsUntil(epochSeconds: String?): Long? =
        epochSeconds?.toLongOrNull()?.minus(clock())

    private suspend fun refreshToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, refreshToken: String): String {
        require(refreshToken.isNotBlank())

        val nonce = authApi.fetchNonce(nonceEndpoint)
        return requestAccessToken("refresh_token", tokenEndpoint, nonce, params, refreshToken)
    }

    private suspend fun issueNewAccessToken(
        tokenEndpoint: String,
        nonceEndpoint: String,
        params: AccessTokenParams,
        dpopKey: String,
    ): String {
        val nonce = authApi.fetchNonce(nonceEndpoint)

        // Abb-ZETA-OIDC-Token-Bezug (08) DPoP key pair; the proof is built per request by dpopProvider
        val dpopKeyGenerated = tpmProvider.generateDpopKey()

        val request = TokenIssuanceRequest(
            tokenEndpoint = tokenEndpoint,
            nonce = nonce,
            dpopProvider = { htu, tokenToHash ->
                val ath = tokenToHash?.let { hash(it) }
                createDpopToken(dpopKeyGenerated.jwk, "POST", htu, nonce, ath)
            },
            clientAssertionProvider = {
                attestationApi.createClientAssertion(
                    productId = params.productId,
                    productVersion = params.productVersion,
                    nonce = nonce,
                    clientId = params.clientId,
                    exp = clock() + params.expiration,
                    aud = tokenEndpoint,
                    platformProductId = params.platformProductId,
                )
            },
            params = params,
        )

        return when (val provider = authConfig.subjectTokenProvider) {
            is SubjectTokenProvider -> subjectTokenIssuance.issue(request, dpopKey, provider)
            is OidcTokenProvider -> when (val result = oidcTokenIssuance.issue(request, provider)) {
                is TokenIssuanceResult.Issued -> result.accessToken
                is TokenIssuanceResult.EmailBindingRequired -> oidcTokenIssuance.completeEmailBinding(
                    pending = result,
                    request = request,
                    otpCallback = provider.config.otpCallback,
                )
            }
        }
    }

    private suspend fun requestAccessToken(
        grantType: String,
        tokenEndpoint: String,
        nonce: ByteArray,
        params: AccessTokenParams,
        refreshToken: String? = null,
        subjectToken: suspend () -> String? = { null },
        subjectTokenType: String? = null,
    ): String {
        try {
            val clientAssertion = createClientAssertion(tokenEndpoint, nonce, params)
            val dpop = createDpopForTokenEndpoint(tokenEndpoint, nonce)
            val subjectTokenValue = subjectToken()
            val request = buildAccessTokenRequest(grantType, params, clientAssertion, refreshToken, subjectTokenValue, subjectTokenType)
            val resp = authApi.requestAccessToken(tokenEndpoint, request, dpop)

            resp.zetaAttestationToken?.let { attestationState?.setZetaAttestationToken(it) }
            val (accessExpiresAt, refreshExpiresAt) = computeTokenExpirations(clock(), resp.expiresIn, resp.refreshExpires)

            authStorage.saveAccessTokens(resp.accessToken, resp.refreshToken, accessExpiresAt, refreshExpiresAt)

            return resp.accessToken
        } catch (e: AuthenticationException) {
            Log.w { "requestAccessToken FAILED: ${e.message}" }
            throw e
        }
    }

    private suspend fun createClientAssertion(tokenEndpoint: String, nonce: ByteArray, params: AccessTokenParams): String =
        attestationApi.createClientAssertion(
            productId = params.productId,
            productVersion = params.productVersion,
            nonce = nonce,
            clientId = params.clientId,
            exp = clock() + params.expiration,
            aud = tokenEndpoint,
            platformProductId = params.platformProductId,
        )

    private suspend fun createDpopForTokenEndpoint(tokenEndpoint: String, nonce: ByteArray): String {
        val dpopKey = tpmProvider.generateDpopKey()
        return createDpopToken(dpopKey.jwk, "POST", tokenEndpoint, nonce)
    }

    private fun buildAccessTokenRequest(
        grantType: String,
        params: AccessTokenParams,
        clientAssertion: String,
        refreshToken: String?,
        subjectToken: String?,
        subjectTokenType: String?,
    ): AccessTokenRequest = AccessTokenRequest(
        grantType = grantType,
        clientId = params.clientId,
        requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
        clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        clientAssertion = clientAssertion,
        scope = params.scopes.joinToString(" "),
        refreshToken = refreshToken,
        subjectToken = subjectToken,
        subjectTokenType = subjectTokenType,
        audience = params.audience,
    )

    override suspend fun createDpopToken(dpopKey: Jwk, method: String, url: String, nonceBytes: ByteArray?, accessTokenHash: String?): String {
        val now = clock()
        val jti = tpmProvider.randomUuid().toHexDashString()
        val htu = url.applyHtuRules()
        val nonce = nonceBytes?.let { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(nonceBytes) }

        val token = AccessTokenUtility.create(
            DPopTokenHeader(
                typ = TokenType.DPOP,
                jwk = dpopKey,
                alg = AsymAlg.ES256,
            ),
            DPoPTokenClaims(
                iat = now,
                jti = jti,
                htm = method,
                htu = htu,
                nonce = nonce,
                ath = accessTokenHash,
            ),
        )
        val signed = signDpopToken(token)
        return AccessTokenUtility.addSignature(token, signed)
    }

    /** The base64url-encoded SHA-256 hash of the token. */
    override suspend fun hash(token: String): String {
        val hashedToken = hashWithSha256(token.encodeToByteArray())
        return Base64
            .UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(hashedToken)
    }

    private suspend fun signDpopToken(token: String): String {
        val signature = tpmProvider.signWithDpopKey(token.toByteArray(), resource)
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(signature)
    }

    companion object {
        private const val SAFETY_MARGIN_SECS = 10

        fun String.applyHtuRules(): String {
            return this.substringBefore('?')
                .substringBefore('#')
                .toHttpsForWsDpop()
        }

        fun String.toHttpsForWsDpop(): String {
            return this.replace("wss://", "https://")
                .replace("ws://", "http://")
        }
    }
}

internal fun computeTokenExpirations(
    now: Long,
    expiresIn: Int,
    refreshExpiresIn: Int?,
): TokenExpirations {
    val accessExpiresAt = now + expiresIn
    val refreshExpiresAt = refreshExpiresIn?.takeIf { it > 0 }?.let { now + it }

    return TokenExpirations(accessExpiresAt, refreshExpiresAt)
}

internal data class TokenExpirations(
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long?,
)
