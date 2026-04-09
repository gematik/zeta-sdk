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

import AttestationApi
import AttestationApiImpl
import Jwk
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.model.DPoPTokenClaims
import de.gematik.zeta.sdk.authentication.model.DPopTokenHeader
import de.gematik.zeta.sdk.authentication.model.TokenType
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.utils.io.core.toByteArray
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock.System
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

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
    private val clock: () -> Long = { System.now().epochSeconds },
    private val tpmProvider: TpmProvider,
) : AccessTokenProvider {

    open val attestationApi: AttestationApi by lazy {
        AttestationApiImpl(
            tpmProvider = tpmProvider,
            attestationConfig = authConfig.attestation,
        )
    }

    override suspend fun getValidToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, dpopKey: String): String {
        val start = TimeSource.Monotonic.markNow()
        val (cached, cacheReadTime) = measureTimedValue { authStorage.getAccessToken(resource) }
        val (exp, expReadTime) = measureTimedValue { authStorage.getTokenExpiration(resource) }
        Log.i { "[AUTH-TIMING] getValidToken cacheRead=$cacheReadTime expRead=$expReadTime" }

        if (cached != null && exp != null && exp != "" && exp.toLong() - clock() > SAFETY_MARGIN_SECS) {
            Log.i { "[AUTH-TIMING] getValidToken CACHE_HIT total=${start.elapsedNow()}" }
            return cached
        }

        val (refreshToken, refreshReadTime) = measureTimedValue { authStorage.getRefreshToken(resource) }
        Log.i { "[AUTH-TIMING] getValidToken refreshTokenRead=$refreshReadTime hasRefresh=${!refreshToken.isNullOrBlank()}" }

        if (!refreshToken.isNullOrBlank()) {
            return try {
                Log.i { "Access token expired, fetching refresh token" }
                val (token, refreshTime) = measureTimedValue {
                    refreshToken(tokenEndpoint, nonceEndpoint, params, refreshToken)
                }
                Log.i { "[AUTH-TIMING] getValidToken REFRESH total=${start.elapsedNow()} refreshFlow=$refreshTime" }
                token
            } catch (ex: Exception) {
                Log.i { "Refresh token failed: (${ex.message})" }
                val (token, newTokenTime) = measureTimedValue {
                    issueNewAccessToken(tokenEndpoint, nonceEndpoint, params, dpopKey)
                }
                Log.i { "[AUTH-TIMING] getValidToken REFRESH_FAILED_NEW_TOKEN total=${start.elapsedNow()} newTokenFlow=$newTokenTime" }
                token
            }
        }

        Log.i { "No refresh token found, getting new access token" }
        val (token, newTokenTime) = measureTimedValue {
            issueNewAccessToken(tokenEndpoint, nonceEndpoint, params, dpopKey)
        }
        Log.i { "[AUTH-TIMING] getValidToken NEW_TOKEN total=${start.elapsedNow()} newTokenFlow=$newTokenTime" }
        return token
    }

    private suspend fun refreshToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, refreshToken: String): String {
        require(refreshToken.isNotBlank())

        val (nonce, nonceTime) = measureTimedValue { authApi.fetchNonce(nonceEndpoint) }
        Log.i { "[AUTH-TIMING] refreshToken fetchNonce=$nonceTime" }
        return requestAccessToken("refresh_token", tokenEndpoint, nonce, params, refreshToken)
    }

    private suspend fun issueNewAccessToken(
        tokenEndpoint: String,
        nonceEndpoint: String,
        params: AccessTokenParams,
        dpopKey: String,
    ): String {
        val start = TimeSource.Monotonic.markNow()
        val (nonce, nonceTime) = measureTimedValue { authApi.fetchNonce(nonceEndpoint) }
        Log.i { "[AUTH-TIMING] issueNewAccessToken fetchNonce=$nonceTime" }

        val subjectToken = suspend {
            val (token, subjectTime) = measureTimedValue {
                authConfig.subjectTokenProvider.createSubjectToken(
                    params.clientId,
                    dpopKey,
                    nonce,
                    params.audience,
                    clock(),
                    authConfig.exp,
                    tpmProvider,
                )
            }
            Log.i { "[AUTH-TIMING] issueNewAccessToken createSubjectToken=$subjectTime" }
            token
        }

        val (result, requestTime) = measureTimedValue {
            requestAccessToken(
                grantType = "urn:ietf:params:oauth:grant-type:token-exchange",
                tokenEndpoint = tokenEndpoint,
                nonce = nonce,
                params = params,
                subjectToken = subjectToken,
                subjectTokenType = "urn:ietf:params:oauth:token-type:jwt",
            )
        }
        Log.i { "[AUTH-TIMING] issueNewAccessToken requestAccessToken=$requestTime total=${start.elapsedNow()}" }
        return result
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
        val start = TimeSource.Monotonic.markNow()
        val requestId = Random.nextInt()
        try {
            val (clientAssertion, assertionTime) = measureTimedValue {
                attestationApi.createClientAssertion(
                    params.productId,
                    params.productVersion,
                    nonce,
                    params.clientId,
                    clock() + params.expiration,
                    tokenEndpoint,
                    params.platformProductId,
                )
            }
            Log.i { "[AUTH-TIMING][$requestId] requestAccessToken createClientAssertion=$assertionTime" }

            val dpopKey = tpmProvider.generateDpopKey()
            val (dpop, dpopTime) = measureTimedValue { createDpopToken(dpopKey.jwk, "POST", tokenEndpoint, nonce) }
            Log.i { "[AUTH-TIMING][$requestId] requestAccessToken createDpopToken=$dpopTime" }

            val (subjectTokenValue, subjectTokenTime) = measureTimedValue { subjectToken() }
            Log.i { "[AUTH-TIMING][$requestId] requestAccessToken subjectToken=$subjectTokenTime" }

            val request = AccessTokenRequest(
                grantType = grantType,
                clientId = params.clientId,
                requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
                clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                clientAssertion = clientAssertion,
                scope = params.scopes.joinToString(" "),
                refreshToken = refreshToken,
                subjectToken = subjectTokenValue,
                subjectTokenType = subjectTokenType,
            )

            val sendEpoch = clock()
            Log.i { "[AUTH-TIMING][$requestId] requestAccessToken sending_http epoch=$sendEpoch monotonic=${start.elapsedNow()}" }

            val (resp, httpTime) = measureTimedValue { authApi.requestAccessToken(tokenEndpoint, request, dpop) }

            val recvEpoch = clock()
            Log.i { "[AUTH-TIMING][$requestId] requestAccessToken received_http epoch=$recvEpoch httpTime=$httpTime" }

            val (_, saveTime) = measureTimedValue {
                authStorage.saveAccessTokens(resource, resp.accessToken, resp.refreshToken, clock() + resp.expiresIn)
            }
            Log.i { "[AUTH-TIMING][$requestId] requestAccessToken saveTokens=$saveTime total=${start.elapsedNow()}" }

            return resp.accessToken
        } catch (e: AuthenticationException) {
            Log.w { "[AUTH-TIMING][$requestId] requestAccessToken FAILED in ${start.elapsedNow()}: ${e.message}" }
            throw e
        }
    }

    override suspend fun createDpopToken(dpopKey: Jwk, method: String, url: String, nonceBytes: ByteArray?, accessTokenHash: String?): String {
        val start = TimeSource.Monotonic.markNow()

        val now = clock()
        val jti = tpmProvider.randomUuid().toHexDashString()
        val htu = url.applyHtuRules()
        val nonce = nonceBytes?.let { Base64.encode(nonceBytes) }

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
        val (signed, signTime) = measureTimedValue { signDpopToken(token) }
        Log.i { "[AUTH-TIMING] createDpopToken signDpopToken=$signTime total=${start.elapsedNow()}" }
        return AccessTokenUtility.addSignature(token, signed)
    }

    /** The base64url-encoded SHA-256 hash of the token. */
    override suspend fun hash(token: String): String {
        val (result, hashTime) = measureTimedValue {
            val hashedToken = hashWithSha256(token.encodeToByteArray())
            Base64
                .UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT)
                .encode(hashedToken)
        }
        Log.i { "[AUTH-TIMING] hash=$hashTime" }
        return result
    }

    private suspend fun signDpopToken(token: String): String {
        val signature = tpmProvider.signWithDpopKey(token.toByteArray())
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
