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
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
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
) : CapabilityHandler {
    companion object {
        private const val AUTHENTICATION_ERROR_CODE = "AUTHENTICATION_ERROR"
    }

    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.Authentication

    override suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult {
        val start = TimeSource.Monotonic.markNow()
        return try {
            return CapabilityResult.RetryRequest { req ->
                if (!req.headers.contains(HttpHeaders.Authorization)) {
                    val dpopKey = tpmProvider.generateDpopKey()

                    val (authToken, authTokenTime) = measureTimedValue { getAuthToken(ctx, dpopKey.jwk.kid) }
                    Log.i { "[ENSURE-AUTH-TIMING] getAuthToken=$authTokenTime" }

                    val (hashedToken, hashTime) = measureTimedValue { tokenProvider.hash(authToken) }
                    Log.i { "[ENSURE-AUTH-TIMING] hashToken=$hashTime" }

                    val dpop = tokenProvider.createDpopToken(dpopKey.jwk, req.method.value, req.url.toString(), null, hashedToken)

                    req.headers[HttpHeaders.Authorization] = "${HttpAuthHeaders.Dpop} $authToken"
                    req.headers[HttpAuthHeaders.Dpop] = dpop
                }
                Log.i { "[ENSURE-AUTH-TIMING] handle total=${start.elapsedNow()}" }
            }
        } catch (e: AuthenticationException) {
            Log.e { "[ENSURE-AUTH-TIMING] handle FAILED in ${start.elapsedNow()}: ${e.message}" }
            CapabilityResult.Error(AUTHENTICATION_ERROR_CODE, e.message.toString(), e.response)
        }
    }

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
            requireNotNull(ctx.configurationStorage.getAuthServer(ctx.resource)) {
                "Missing auth server configuration for resource: ${ctx.resource}"
            }
        }
        val tokenEndpoint = requireNotNull(authServer.tokenEndpoint) {
            "Missing token endpoint for resource: ${ctx.resource}"
        }
        val nonceEndpoint = requireNotNull(authServer.nonceEndpoint) {
            "Missing nonce endpoint for resource: ${ctx.resource}"
        }
        val (clientId, clientIdTime) = measureTimedValue {
            requireNotNull(ctx.clientRegistrationStorage.getClientId(authServer.issuer)) {
                "Missing client_id for resource ${ctx.resource}"
            }
        }
        val issuer = requireNotNull(ctx.configurationStorage.getAuthServer(ctx.resource)?.issuer) {
            "Missing issuer for resource: $ctx.resource"
        }
        val scopes = authConfig.scopes.ifEmpty {
            requireNotNull(authServer.scopesSupported) {
                "Missing scopes supported for resource: ${ctx.resource}"
            }
        }
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
                audience = audienceFromIssuer(issuer),
                platformProductId,
            ),
        )
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

fun audienceFromIssuer(issuer: String): String {
    val authUrl = Url(issuer)

    return URLBuilder().apply {
        protocol = authUrl.protocol
        host = authUrl.host
        port = authUrl.port
        encodedPath = "/auth/"
    }.buildString()
}
