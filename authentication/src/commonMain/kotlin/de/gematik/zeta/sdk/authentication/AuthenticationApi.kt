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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

interface AuthenticationApi {
    suspend fun fetchNonce(nonceEndpoint: String): ByteArray
    suspend fun authenticateExternal(hashedToken: String): String
    suspend fun requestAccessToken(
        fromEndpoint: String,
        accessTokenRequest: AccessTokenRequest,
        dpopToken: String,
    ): AccessTokenResponse
}

@Suppress("UnusedPrivateProperty")
class AuthenticationApiImpl(
    private val httpClientConfig: ZetaHttpClientBuilder,
) : AuthenticationApi {

    override suspend fun fetchNonce(nonceEndpoint: String): ByteArray {
        val response = buildHttpClient(nonceEndpoint)
            .get("")

        val body = response.bodyAsText()

        return Base64.UrlSafe
            .withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            .decode(body)
    }
    override suspend fun authenticateExternal(hashedToken: String): String {
        TODO("Not yet implemented")
        return "signed"
    }

    override suspend fun requestAccessToken(
        fromEndpoint: String,
        accessTokenRequest: AccessTokenRequest,
        dpopToken: String,
    ): AccessTokenResponse {
        val response: ZetaHttpResponse = buildHttpClient(fromEndpoint)
            .submitForm(
                "",
                accessTokenRequest.toParameters(),
            ) {
                headers[HttpAuthHeaders.Dpop] = dpopToken
            }

        // Handle auth response
        return handleResponse(response.raw)
    }

    private fun buildHttpClient(fromEndpoint: String): ZetaHttpClient {
        return httpClientConfig.build(fromEndpoint)
    }

    private suspend fun handleResponse(response: HttpResponse): AccessTokenResponse {
        if (response.status == HttpStatusCode.OK) {
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

            return AccessTokenResponse(accessToken, expiresIn, refreshExpiresIn, tokenType, notBeforePolicy, sessionState, scope, issuedTokenType, refreshToken)
        } else {
            Log.e { "Invalid token response: ${response.status.value}" }
            error("Failed to obtain authentication token")
        }
    }
}
