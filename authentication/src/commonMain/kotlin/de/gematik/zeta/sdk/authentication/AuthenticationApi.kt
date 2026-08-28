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
import de.gematik.zeta.sdk.authentication.oidc.BindEmailRequest
import de.gematik.zeta.sdk.authentication.oidc.BindEmailResponse
import de.gematik.zeta.sdk.authentication.oidc.OidcAccessTokenResponse
import de.gematik.zeta.sdk.authentication.oidc.OidcErrorResponse
import de.gematik.zeta.sdk.authentication.oidc.OidcTokenIssuance
import de.gematik.zeta.sdk.authentication.oidc.OtpVerifyRequest
import de.gematik.zeta.sdk.authentication.oidc.ParResponse
import de.gematik.zeta.sdk.authentication.oidc.VerifyOtpResponse
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Clock

interface AuthenticationApi {
    suspend fun fetchNonce(nonceEndpoint: String): ByteArray
    suspend fun requestAccessToken(
        fromEndpoint: String,
        accessTokenRequest: AccessTokenRequest,
        dpopToken: String,
    ): AccessTokenResponse

    suspend fun requestOidcToken(
        fromEndpoint: String,
        accessTokenRequest: OidcTokenIssuance.OidcTokenRequest,
        dpopToken: String,
    ): OidcAccessTokenResponse

    suspend fun postBindEmail(
        endpoint: String,
        accessToken: String,
        dpop: String,
        body: BindEmailRequest,
    ): BindEmailResponse

    suspend fun postResendOtp(endpoint: String, accessToken: String, dpop: String): BindEmailResponse

    suspend fun postVerifyOtp(
        endpoint: String,
        accessToken: String,
        dpop: String,
        body: OtpVerifyRequest,
    ): VerifyOtpResponse

    suspend fun requestPar(endpoint: String, parameters: Parameters): ParResponse
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
        val json = submitTokenRequest(fromEndpoint, accessTokenRequest.toParameters(), dpopToken)
        return parseAccessTokenResponse(json)
    }

    override suspend fun requestOidcToken(
        fromEndpoint: String,
        accessTokenRequest: OidcTokenIssuance.OidcTokenRequest,
        dpopToken: String,
    ): OidcAccessTokenResponse {
        val json = submitTokenRequest(fromEndpoint, accessTokenRequest.toParameters(), dpopToken)
        return parseOidcAccessTokenResponse(json)
    }

    override suspend fun postBindEmail(
        endpoint: String,
        accessToken: String,
        dpop: String,
        body: BindEmailRequest,
    ): BindEmailResponse {
        val parameters = Parameters.build {
            body.email?.let { append("email", it) }
        }
        val response = submitFormRequest(endpoint, accessToken, dpop, parameters)
        ensureSuccess(response, HttpStatusCode.Accepted)
        return Json.decodeFromString(response.bodyAsText())
    }

    override suspend fun postResendOtp(
        endpoint: String,
        accessToken: String,
        dpop: String,
    ): BindEmailResponse {
        val response = submitFormRequest(endpoint, accessToken, dpop, Parameters.Empty)

        ensureSuccess(response, HttpStatusCode.Accepted)

        return Json.decodeFromString(response.bodyAsText())
    }

    override suspend fun postVerifyOtp(
        endpoint: String,
        accessToken: String,
        dpop: String,
        body: OtpVerifyRequest,
    ): VerifyOtpResponse {
        val parameters = Parameters.build {
            append("code", body.otp)
        }
        val response = submitFormRequest(endpoint, accessToken, dpop, parameters)

        ensureSuccess(response, HttpStatusCode.OK)

        return Json.decodeFromString(response.bodyAsText())
    }

    override suspend fun requestPar(endpoint: String, parameters: Parameters): ParResponse {
        val response = zetaHttpClient.submitForm(endpoint, parameters)
        if (!response.status.isSuccess()) {
            val error = runCatching { Json.decodeFromString<OidcErrorResponse>(response.bodyAsText()) }.getOrNull()
            throw AuthenticationException(
                response.raw,
                "PAR request failed: ${response.status.value} ${error?.error ?: "unknown_error"} - ${error?.errorDescription ?: "no description"}",
            )
        }
        return Json.decodeFromString(response.bodyAsText())
    }

    private suspend fun submitFormRequest(
        endpoint: String,
        accessToken: String,
        dpop: String,
        parameters: Parameters,
    ): ZetaHttpResponse {
        val sendTime = Clock.System.now()
        Log.d { "[BIND-SEND] endpoint=$endpoint time=$sendTime" }

        val response: ZetaHttpResponse = zetaHttpClient
            .submitForm(endpoint, parameters) {
                headers[HttpAuthHeaders.Dpop] = dpop
                headers[HttpHeaders.Authorization] = "DPoP $accessToken"
            }

        val recvTime = Clock.System.now()

        Log.d { "[BIND-RECV] endpoint=$endpoint time=$recvTime duration=${recvTime - sendTime} status=${response.status}" }

        return response
    }

    private suspend fun submitTokenRequest(
        fromEndpoint: String,
        parameters: Parameters,
        dpopToken: String,
    ): JsonObject {
        val sendTime = Clock.System.now()
        Log.d { "[TOKEN-SEND] endpoint=$fromEndpoint time=$sendTime" }
        val response: ZetaHttpResponse = zetaHttpClient
            .submitForm(fromEndpoint, parameters) {
                headers[HttpAuthHeaders.Dpop] = dpopToken
            }
        val recvTime = Clock.System.now()
        Log.d { "[TOKEN-RECV] endpoint=$fromEndpoint time=$recvTime duration=${recvTime - sendTime} status=${response.status}" }
        return handleStatus(response)
    }

    private suspend fun ensureSuccess(response: ZetaHttpResponse, vararg expected: HttpStatusCode) {
        if (response.status in expected) return
        when (response.status) {
            HttpStatusCode.Unauthorized -> {
                val bodyText = response.bodyAsText()
                if (bodyText.contains("invalid_client")) {
                    throw InvalidClientException(response.raw, bodyText)
                } else {
                    throw RecoverableAuthenticationException(response.raw, bodyText)
                }
            }
            HttpStatusCode.Forbidden -> {
                throw NonRecoverableAuthenticationException(response.raw, response.bodyAsText())
            }
            else -> {
                Log.e { "Unexpected authentication error: [${response.status.value}] ${response.status.description}" }
                throw AuthenticationException(response.raw, response.bodyAsText())
            }
        }
    }

    private suspend fun handleStatus(response: ZetaHttpResponse): JsonObject {
        return when (response.status) {
            HttpStatusCode.OK -> Json.parseToJsonElement(response.bodyAsText()).jsonObject
            HttpStatusCode.Unauthorized -> {
                val bodyText = response.bodyAsText()
                if (bodyText.contains("invalid_client")) {
                    throw InvalidClientException(response.raw, bodyText)
                } else {
                    throw RecoverableAuthenticationException(response.raw, bodyText)
                }
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

    private fun parseAccessTokenResponse(json: JsonObject): AccessTokenResponse = AccessTokenResponse(
        accessToken = json["access_token"]?.jsonPrimitive?.content ?: "",
        expiresIn = json["expires_in"]?.jsonPrimitive?.int ?: 0,
        refreshExpires = json["refresh_expires_in"]?.jsonPrimitive?.int ?: 0,
        tokenType = json["token_type"]?.jsonPrimitive?.content ?: "",
        notBeforePolicy = json["not-before-policy"]?.jsonPrimitive?.content ?: "",
        sessionState = json["session_state"]?.jsonPrimitive?.content ?: "",
        scope = json["scope"]?.jsonPrimitive?.content ?: "",
        issuedTokenType = json["issued_token_type"]?.jsonPrimitive?.content ?: "",
        refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: "",
    )

    private fun parseOidcAccessTokenResponse(json: JsonObject): OidcAccessTokenResponse = OidcAccessTokenResponse(
        accessToken = json["access_token"]?.jsonPrimitive?.content ?: "",
        expiresIn = json["expires_in"]?.jsonPrimitive?.int ?: 0,
        refreshExpires = json["refresh_expires_in"]?.jsonPrimitive?.int,
        tokenType = json["token_type"]?.jsonPrimitive?.content ?: "",
        scope = json["scope"]?.jsonPrimitive?.content ?: "",
        refreshToken = json["refresh_token"]?.jsonPrimitive?.content,
        emailHint = json["email_hint"]?.jsonPrimitive?.content,
        bindingMode = json["binding_mode"]?.jsonPrimitive?.content,
    )
}

open class AuthenticationException(val response: HttpResponse?, message: String) : Exception(message)
open class RecoverableAuthenticationException(response: HttpResponse?, message: String) : AuthenticationException(response, message)
class InvalidClientException(response: HttpResponse?, message: String) : RecoverableAuthenticationException(response, message)
class NonRecoverableAuthenticationException(response: HttpResponse, message: String) : AuthenticationException(response, message)
