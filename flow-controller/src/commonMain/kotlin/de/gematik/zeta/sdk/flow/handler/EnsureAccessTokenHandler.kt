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

package de.gematik.zeta.sdk.flow.handler

import PublicKeyOut
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthenticationException
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.authentication.InvalidClientException
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.http.HttpHeaders
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

@Suppress("FunctionOnlyReturningConstant")
class EnsureAccessTokenHandler(
    val tokenProvider: AccessTokenProvider,
    val tpmProvider: TpmProvider,
    val authConfig: AuthConfig,
    val productId: String,
    val productVersion: String,
    val platformProductId: PlatformProductId,
    val clientRegistrationHandler: ClientRegistrationHandler? = null,
) : CapabilityHandler {
    companion object {
        private const val AUTHENTICATION_ERROR_CODE = "AUTHENTICATION_ERROR"
    }

    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.Authentication

    override suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult {
        val start = TimeSource.Monotonic.markNow()
        val firstResult = fetchToken(ctx)

        return when {
            firstResult.isSuccess ->
                buildRetryRequest(firstResult.getOrThrow(), start)

            firstResult.isUnauthorized() -> {
                Log.w { "Token request got 401: step up" }
                stepUp(ctx, firstResult.isInvalidClient(), start)
            }

            else -> {
                val ex = firstResult.exceptionOrNull() as AuthenticationException
                Log.e { "Token request failed: ${ex.message}" }
                CapabilityResult.Error(AUTHENTICATION_ERROR_CODE, ex.message.toString(), ex.response)
            }
        }
    }

    @Suppress("InstanceOfCheckForException")
    suspend fun getValidAccessTokenWithStepUp(ctx: FlowContext): AccessTokenWithDpopKey {
        return try {
            getValidAccessToken(ctx)
        } catch (e: RecoverableAuthenticationException) {
            Log.w { "WS auth got 401, stepping up" }
            val start = TimeSource.Monotonic.markNow()
            val result = stepUp(ctx, e is InvalidClientException, start)
            if (result is CapabilityResult.RetryRequest) {
                getValidAccessToken(ctx)
            } else {
                throw e
            }
        }
    }

    private suspend fun stepUp(
        ctx: FlowContext,
        invalidClient: Boolean,
        start: TimeSource.Monotonic.ValueTimeMark,
    ): CapabilityResult {
        ctx.authenticationStorage.clear()
        if (invalidClient && clientRegistrationHandler != null) {
            ctx.clientRegistrationStorage.clear()
            val registration = clientRegistrationHandler.handle(FlowNeed.ClientRegistration, ctx)
            if (registration is CapabilityResult.Error) {
                Log.e { "Step-up re-registration failed (${registration.internalCode}); not requesting a token" }
                return registration
            }
        } else if (invalidClient) {
            Log.w { "invalid_client but no clientRegistrationHandler available - skipping re-registration" }
        }

        val retryResult = fetchToken(ctx)

        return when {
            retryResult.isSuccess -> {
                Log.i { "Step-up succeeded" }
                buildRetryRequest(retryResult.getOrThrow(), start)
            }
            else -> {
                val ex = retryResult.exceptionOrNull() as AuthenticationException
                Log.e { "Step-up failed: ${ex.message}" }
                CapabilityResult.Error(AUTHENTICATION_ERROR_CODE, ex.message.toString(), ex.response)
            }
        }
    }

    private suspend fun fetchToken(ctx: FlowContext): Result<TokenWithDpop> {
        return try {
            val dpopKey = tpmProvider.generateDpopKey()
            val (authToken, tokenTime) = measureTimedValue { getAuthToken(ctx, dpopKey.jwk.kid) }
            Log.i { "[ENSURE-AUTH-TIMING] getAuthToken=$tokenTime" }

            val (hashedToken, hashTime) = measureTimedValue { tokenProvider.hash(authToken) }
            Log.i { "[ENSURE-AUTH-TIMING] hashToken=$hashTime" }

            Result.success(TokenWithDpop(authToken, hashedToken, dpopKey))
        } catch (e: AuthenticationException) {
            Result.failure(e)
        }
    }

    private fun buildRetryRequest(
        token: TokenWithDpop,
        start: TimeSource.Monotonic.ValueTimeMark,
    ): CapabilityResult = CapabilityResult.RetryRequest { req ->
        req.headers.remove(HttpHeaders.Authorization)
        req.headers.remove(HttpAuthHeaders.Dpop)

        val dpop = tokenProvider.createDpopToken(
            token.dpopKey.jwk,
            req.method.value,
            req.url.toString(),
            null,
            token.hashedToken,
        )

        req.headers[HttpHeaders.Authorization] = "${HttpAuthHeaders.Dpop} ${token.authToken}"
        req.headers[HttpAuthHeaders.Dpop] = dpop

        Log.i { "[ENSURE-AUTH-TIMING] handle total=${start.elapsedNow()}" }
    }

    private data class TokenWithDpop(
        val authToken: String,
        val hashedToken: String,
        val dpopKey: PublicKeyOut,
    )

    private fun Result<*>.isUnauthorized(): Boolean =
        exceptionOrNull() is RecoverableAuthenticationException

    private fun Result<*>.isInvalidClient(): Boolean =
        exceptionOrNull() is InvalidClientException

    suspend fun getAuthToken(ctx: FlowContext, dpopKey: String): String {
        val start = TimeSource.Monotonic.markNow()
        val (cfg, buildParamsTime) = measureTimedValue { buildAccessTokenParams(ctx) }
        Log.i { "[ENSURE-AUTH-TIMING] buildAccessTokenParams=$buildParamsTime" }

        val (token, tokenTime) = measureTimedValue {
            tokenProvider.getValidToken(
                cfg.tokenEndpoint,
                cfg.nonceEndpoint,
                cfg.params,
                dpopKey,
            )
        }
        Log.i { "[ENSURE-AUTH-TIMING] getValidToken=$tokenTime getAuthToken_total=${start.elapsedNow()}" }
        return token
    }

    private suspend fun buildAccessTokenParams(ctx: FlowContext): AccessTokenParamsWithEndpoints {
        val start = TimeSource.Monotonic.markNow()
        val (authServer, authServerTime) = measureTimedValue {
            requireNotNull(ctx.configurationStorage.getAuthServer()) {
                "Missing auth server configuration for resource: ${ctx.resourceScope.storageKey}"
            }
        }
        val tokenEndpoint = requireNotBlank(authServer.tokenEndpoint) {
            "Missing token endpoint for resource"
        }
        val nonceEndpoint = requireNotBlank(authServer.nonceEndpoint) {
            "Missing nonce endpoint for resource"
        }
        val (clientId, clientIdTime) = measureTimedValue {
            requireNotBlank(
                ctx.clientRegistrationStorage.getClientId(
                    authServer.registrationEndpoint ?: authServer.issuer,
                ),
            ) {
                "Missing client_id for resource"
            }
        }

        val audience = requireNotBlank(ctx.configurationStorage.getProtectedResource()?.resource) {
            "Missing resource value in OPR: ${ctx.resourceScope.storageKey}"
        }

        val scopes = authConfig.scopes.ifEmpty { authServer.scopesSupported }

        Log.i { "[ENSURE-AUTH-TIMING] buildParams authServerRead=$authServerTime clientIdRead=$clientIdTime total=${start.elapsedNow()}" }

        return AccessTokenParamsWithEndpoints(
            tokenEndpoint = tokenEndpoint,
            nonceEndpoint = nonceEndpoint,
            params = AccessTokenParams(
                clientId = clientId,
                productId = productId,
                productVersion = productVersion,
                expiration = authConfig.exp,
                scopes = scopes,
                audience = audience,
                platformProductId,
            ),
        )
    }

    private fun requireNotBlank(value: String?, lazyMessage: () -> String): String {
        val v = requireNotNull(value, lazyMessage)
        require(v.isNotBlank(), lazyMessage)
        return v
    }

    private data class AccessTokenParamsWithEndpoints(
        val tokenEndpoint: String,
        val nonceEndpoint: String,
        val params: AccessTokenParams,
    )

    data class AccessTokenWithDpopKey(val token: String, val dpopKey: PublicKeyOut)

    /** Helper you can call from ws() to get a valid access token */
    suspend fun getValidAccessToken(ctx: FlowContext): AccessTokenWithDpopKey {
        val dpopKey = tpmProvider.generateDpopKey()
        val token = getAuthToken(ctx, dpopKey.jwk.kid)
        return AccessTokenWithDpopKey(token, dpopKey)
    }
}
