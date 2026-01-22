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

import de.gematik.zeta.sdk.attestation.model.ClientSelfAssessment
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthenticationException
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath

@Suppress("FunctionOnlyReturningConstant")
class EnsureAccessTokenHandler(
    val tokenProvider: AccessTokenProvider,
    val authConfig: AuthConfig,
    val productId: String,
    val productVersion: String,
    val clientSelfAssessment: ClientSelfAssessment,
) : CapabilityHandler {
    companion object {
        private const val AUTHENTICATION_ERROR_CODE = "AUTHENTICATION_ERROR"
    }

    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.Authentication

    override suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult {
        return try {
            val authToken = getAuthToken(ctx)
            val hashedToken = tokenProvider.hash(authToken)

            return CapabilityResult.RetryRequest { req ->
                if (!req.headers.contains(HttpHeaders.Authorization)) {
                    val dpop = tokenProvider.createDpopToken(req.method.value, req.url.toString(), null, hashedToken)
                    req.headers[HttpHeaders.Authorization] = "${HttpAuthHeaders.Dpop} $authToken"
                    req.headers[HttpAuthHeaders.Dpop] = dpop
                }
            }
        } catch (e: AuthenticationException) {
            CapabilityResult.Error(AUTHENTICATION_ERROR_CODE, e.message.toString(), e.response)
        }
    }

    suspend fun getAuthToken(ctx: FlowContext): String {
        val cfg = buildAccessTokenParams(ctx)

        return tokenProvider.getValidToken(
            cfg.tokenEndpoint,
            cfg.nonceEndpoint,
            cfg.params,
        )
    }

    private suspend fun buildAccessTokenParams(ctx: FlowContext): AccessTokenParamsWithEndpoints {
        val authServer = requireNotNull(ctx.configurationStorage.getAuthServer(ctx.resource)) {
            "Missing auth server configuration for resource: ${ctx.resource}"
        }
        val tokenEndpoint = requireNotNull(authServer.tokenEndpoint) {
            "Missing token endpoint for resource: ${ctx.resource}"
        }
        val nonceEndpoint = requireNotNull(authServer.nonceEndpoint) {
            "Missing nonce endpoint for resource: ${ctx.resource}"
        }
        val clientId = requireNotNull(ctx.clientRegistrationStorage.getClientId(ctx.resource)) {
            "Missing client_id for resource ${ctx.resource}"
        }
        val issuer = requireNotNull(ctx.configurationStorage.getAuthServer(ctx.resource)?.issuer) {
            "Missing issuer for resource: $ctx.resource"
        }
        val scopes = authConfig.scopes.ifEmpty {
            requireNotNull(authServer.scopesSupported) {
                "Missing scopes supported for resource: ${ctx.resource}"
            }
        }

        val registrationInfo = ctx.clientRegistrationStorage.getRegistrationInfo(ctx.resource)
        checkNotNull(registrationInfo) { "The registration information could not be obtained" }
        checkNotNull(registrationInfo.clientId) { "clientId could not be obtained from the registration" }
        checkNotNull(registrationInfo.clientIdIssuedAt) { "clientIdIssuedAt could not be obtained from the registration" }

        val clientAssessmentWithRegistrationInfo = clientSelfAssessment.copy(clientId = registrationInfo.clientId, registrationTimestamp = registrationInfo.clientIdIssuedAt!!)

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
                clientAssessmentWithRegistrationInfo,
            ),
        )
    }

    private data class AccessTokenParamsWithEndpoints(
        val tokenEndpoint: String,
        val nonceEndpoint: String,
        val params: AccessTokenParams,
    )

    /** Helper you can call from ws() to get a valid access token */
    suspend fun getValidAccessToken(ctx: FlowContext): String = getAuthToken(ctx)
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
