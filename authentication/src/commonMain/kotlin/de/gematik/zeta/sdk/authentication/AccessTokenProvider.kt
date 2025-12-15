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

import AttestationApiImpl
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.model.AccessTokenResponse
import de.gematik.zeta.sdk.authentication.model.DPoPTokenClaims
import de.gematik.zeta.sdk.authentication.model.DPopTokenHeader
import de.gematik.zeta.sdk.authentication.model.TokenType
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.utils.io.core.toByteArray
import kotlin.io.encoding.Base64
import kotlin.time.Clock.System

data class AccessTokenParams(
    val clientId: String,
    val productId: String,
    val productVersion: String,
    val expiration: Long,
    val scopes: List<String>,
    val audience: String,
)

interface AccessTokenProvider {
    suspend fun getValidToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams): String
    suspend fun refreshToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, refreshToken: String): String
    suspend fun createDpopToken(method: String, url: String, nonceBytes: ByteArray? = null, accessTokenHash: String? = null): String
    suspend fun hash(token: String): String
}

@Suppress("FunctionOnlyReturningConstant", "standard:max-line-length")
class AccessTokenProviderImpl(
    private val resource: String,
    private val authConfig: AuthConfig,
    private val authApi: AuthenticationApi,
    private val authStorage: AuthenticationStorage,
    private val clock: () -> Long = { System.now().epochSeconds },
    private val tpmProvider: TpmProvider,
) : AccessTokenProvider {
    override suspend fun getValidToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams): String {
        val cached = authStorage.getAccessToken(resource)
        val exp = authStorage.getTokenExpiration(resource)

        if (cached != null && exp != null && exp != "" && exp.toLong() - clock() > SAFETY_MARGIN_SECS) {
            return cached
        }

        val refreshToken = authStorage.getRefreshToken(resource)
        if (!refreshToken.isNullOrBlank()) {
            return try {
                Log.d { "Access token expired, fetching refresh token" }
                refreshToken(tokenEndpoint, nonceEndpoint, params, refreshToken)
            } catch (ex: Exception) {
                Log.d { "Refresh token failed: (${ex.message})" }
                issueNewAccessToken(tokenEndpoint, nonceEndpoint, params)
            }
        }

        Log.d { "No refresh token found, getting new access token" }
        return issueNewAccessToken(tokenEndpoint, nonceEndpoint, params)
    }

    override suspend fun refreshToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, refreshToken: String): String {
        require(refreshToken.isNotBlank())

        val nonce = authApi.fetchNonce(nonceEndpoint)
        val clientAssertion = AttestationApiImpl(tpmProvider).createClientAssertion(
            params.productId,
            params.productVersion,
            nonce,
            params.clientId,
            clock() + params.expiration,
            tokenEndpoint,
        )

        val dpop = createDpopToken("POST", tokenEndpoint, nonce, null)
        val request = AccessTokenRequest(
            grantType = "refresh_token",
            clientId = params.clientId,
            requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
            clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            clientAssertion = clientAssertion,
            scope = params.scopes.joinToString(" "),
            refreshToken = refreshToken,
            subjectToken = null,
            subjectTokenType = null,
        )
        val resp = authApi.requestAccessToken(tokenEndpoint, request, dpop)
        persist(resp)

        return resp.accessToken
    }

    private suspend fun issueNewAccessToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams): String {
        // 1. nonce → attestation challenge
        val nonce = authApi.fetchNonce(nonceEndpoint)
        Log.d { "issueNewAccessToken: nonce = $nonce" }
        // val attChallenge = calculateAttestationChallenge(nonce)

        // 2. client assertion JWT if your AS requires it
        val clientAssertion = AttestationApiImpl(tpmProvider).createClientAssertion(
            params.productId,
            params.productVersion,
            nonce,
            params.clientId,
            clock() + params.expiration,
            tokenEndpoint,
        )

        // 3. self-made access token (SM(C)-B) to be signed/hashed externally
        val subjectToken = authConfig.subjectTokenProvider.createSubjectToken(
            params.clientId,
            nonce,
            params.audience,
            clock(),
            authConfig.exp,
            tpmProvider,
        )

        // 4. external signature / proof
        // val signature = authenticateExternal(smcbAccessToken)
        // val tokenWithSig = addSignature(smcbAccessToken, signature)

        // 5 Call AS: token-exchange (adjust fields as needed)

        // 26. create DPoP Proof token
        val dpop = createDpopToken("POST", tokenEndpoint, nonce, null)

        // 27. request access token
        val request = AccessTokenRequest(
            grantType = "urn:ietf:params:oauth:grant-type:token-exchange",
            clientId = params.clientId,
            subjectToken = subjectToken,
            subjectTokenType = "urn:ietf:params:oauth:token-type:jwt",
            requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
            clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            clientAssertion = clientAssertion,
            scope = params.scopes.joinToString(" "),
        )
        val resp = authApi.requestAccessToken(tokenEndpoint, request, dpop)
        persist(resp)

        return resp.accessToken
    }

    private suspend fun persist(resp: AccessTokenResponse) {
        val exp = clock() + resp.expiresIn

        authStorage.saveAccessTokens(resource, resp.accessToken, resp.refreshToken, exp)
    }

    override suspend fun createDpopToken(method: String, url: String, nonceBytes: ByteArray?, accessTokenHash: String?): String {
        val dpopKey = tpmProvider.generateDpopKey()
        val now = clock()
        val jti = tpmProvider.randomUuid().toHexDashString()
        val htu = removeUrlQueryParams(url)
        val nonce = nonceBytes?.let { Base64.encode(nonceBytes) }

        val token = AccessTokenUtility.create(
            DPopTokenHeader(
                typ = TokenType.DPOP,
                jwk = dpopKey.jwk,
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
        return AccessTokenUtility.addSignature(token, signDpopToken(token))
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
        val signature = tpmProvider.signWithDpopKey(token.toByteArray())
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(signature)
    }

    fun removeUrlQueryParams(url: String): String {
        return url.substringBefore('?').substringBefore('#')
    }

    companion object {
        private const val SAFETY_MARGIN_SECS = 10
    }
}
