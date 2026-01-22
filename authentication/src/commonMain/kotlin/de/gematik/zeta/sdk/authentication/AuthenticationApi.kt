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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.model.AccessTokenResponse
import de.gematik.zeta.sdk.authentication.model.toParameters
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

interface AuthenticationApi {
    suspend fun fetchNonce(nonceEndpoint: String): ByteArray
    suspend fun requestAccessToken(
        fromEndpoint: String,
        accessTokenRequest: AccessTokenRequest,
        dpopToken: String,
    ): AccessTokenResponse
}

class AuthenticationApiImpl(
    private val zetaHttpClient: ZetaHttpClient,
) : AuthenticationApi {

    override suspend fun fetchNonce(nonceEndpoint: String): ByteArray {
        val response = zetaHttpClient.get(nonceEndpoint)
        return handleNonceResponse(response)
    }
    private suspend fun handleNonceResponse(response: ZetaHttpResponse): ByteArray {
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.bodyAsText()

                return Base64.UrlSafe
                    .withPadding(Base64.PaddingOption.ABSENT)
                    .decode(body)
            }

            else -> {
                Log.e { "Failed to get nonce" }
                throw AuthenticationException(response.raw, response.bodyAsText())
            }
        }
    }

    override suspend fun requestAccessToken(
        fromEndpoint: String,
        accessTokenRequest: AccessTokenRequest,
        dpopToken: String,
    ): AccessTokenResponse {
        val response: ZetaHttpResponse = zetaHttpClient
            .submitForm(
                fromEndpoint,
                accessTokenRequest.toParameters(),
            ) {
                headers[HttpAuthHeaders.Dpop] = dpopToken
            }

        return handleResponse(response)
    }

    private suspend fun handleResponse(response: ZetaHttpResponse): AccessTokenResponse {
        return when (response.status) {
            HttpStatusCode.OK -> {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val accessToken = json["access_token"]?.jsonPrimitive?.content ?: ""
                val expiresIn = json["expires_in"]?.jsonPrimitive?.int ?: 0
                val refreshExpiresIn = json["refresh_expires_in"]?.jsonPrimitive?.int ?: 0
                val tokenType = json["token_type"]?.jsonPrimitive?.content ?: ""
                val notBeforePolicy = json["not-before-policy"]?.jsonPrimitive?.content ?: ""
                val sessionState = json["session_state"]?.jsonPrimitive?.content ?: ""
                val scope = json["scope"]?.jsonPrimitive?.content ?: ""
                val issuedTokenType = json["issued_token_type"]?.jsonPrimitive?.content ?: ""
                val refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: ""

                AccessTokenResponse(accessToken, expiresIn, refreshExpiresIn, tokenType, notBeforePolicy, sessionState, scope, issuedTokenType, refreshToken)
            }

            HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized -> {
                throw RecoverableAuthenticationException(response.raw, response.bodyAsText())
            }

            HttpStatusCode.Forbidden -> {
                throw NonRecoverableAuthenticationException(response.raw, response.bodyAsText())
            }

            else -> {
                Log.e { "Unexpected authentication error:Error: [${response.status.value}] ${response.status.description}" }
                throw AuthenticationException(response.raw, "Client hat keine Berechtigung auf angef. Resource")
            }
        }
    }
}

open class AuthenticationException(val response: HttpResponse, message: String) : Exception(message)
class RecoverableAuthenticationException(response: HttpResponse, message: String) : AuthenticationException(response, message)
class NonRecoverableAuthenticationException(response: HttpResponse, message: String) : AuthenticationException(response, message)
