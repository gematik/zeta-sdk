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

package de.gematik.zeta.sdk.authentication.identity

import de.gematik.zeta.sdk.authentication.DpopProvider
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.authentication.ScopedAccessTokenProvider
import de.gematik.zeta.sdk.authentication.identity.models.ClientManagementScopes
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json

interface ClientManagementApiClient {
    suspend fun listClients(): List<RegisteredClient>
    suspend fun deleteClient(id: String)
    suspend fun changeEmail(newEmail: String): ChangeEmailResponse
    suspend fun resendEmailChangeCode(): EmailChallenge
    suspend fun verifyEmailChange(code: String)
}

class ClientManagement(
    private val api: ClientManagementApiClient,
) {
    suspend fun listClients(): List<RegisteredClient> = api.listClients()
    suspend fun deleteClient(id: String) = api.deleteClient(id)

    suspend fun changeEmail(newEmail: String): ChangeEmailResponse = api.changeEmail(newEmail)
    suspend fun resendEmailChangeCode(): EmailChallenge = api.resendEmailChangeCode()
    suspend fun verifyEmailChange(code: String) = api.verifyEmailChange(code)
}

class ClientManagementApiClientImpl(
    private val httpClient: ZetaHttpClient,
    private val baseUrl: String,
    private val tokenProvider: ScopedAccessTokenProvider,
    private val dpopProvider: DpopProvider,
) : ClientManagementApiClient {

    override suspend fun listClients(): List<RegisteredClient> =
        withToken(HttpMethod.Get, CLIENTS_PATH) { response ->
            when (response.status) {
                HttpStatusCode.OK -> json.decodeFromString(response.bodyAsText())
                else -> throw response.toException()
            }
        }

    override suspend fun changeEmail(newEmail: String): ChangeEmailResponse {
        require(newEmail.isNotBlank()) { "new_email must not be blank" }
        return withToken(
            HttpMethod.Post,
            CHANGE_EMAIL_PATH,
            configure = {
                contentType(ContentType.Application.Json)
                setBody(ChangeEmailRequest(newEmail))
            },
        ) { response ->
            when (response.status) {
                HttpStatusCode.Accepted -> json.decodeFromString(response.bodyAsText())
                else -> throw response.toException()
            }
        }
    }

    override suspend fun resendEmailChangeCode(): EmailChallenge =
        withToken(HttpMethod.Post, RESEND_EMAIL_CODE_PATH, configure = { otpForm() }) { response ->
            when (response.status) {
                HttpStatusCode.Accepted -> json.decodeFromString(response.bodyAsText())
                else -> throw response.toException()
            }
        }

    override suspend fun verifyEmailChange(code: String) {
        require(code.isNotBlank()) { "code must not be blank" }
        withToken(HttpMethod.Post, VERIFY_EMAIL_CODE_PATH, configure = { otpForm("code" to code) }) { response ->
            when (response.status) {
                HttpStatusCode.OK -> {
                    val result = json.decodeFromString<ChangeEmailResponse>(response.bodyAsText())
                    check(result == ChangeEmailResponse.Verified) { "Unexpected response to e-mail verification: $result" }
                }
                else -> throw response.toException()
            }
        }
    }

    override suspend fun deleteClient(id: String) {
        require(id.isNotBlank()) { "client id must not be blank" }
        withToken(
            HttpMethod.Delete,
            "$CLIENTS_PATH/${id.encodeURLPathPart()}",
        ) { response ->
            if (response.status != HttpStatusCode.NoContent) throw response.toException()
        }
    }

    private fun HttpRequestBuilder.otpForm(vararg params: Pair<String, String>) {
        setBody(
            FormDataContent(
                Parameters.build {
                    params.forEach { (name, value) -> append(name, value) }
                    append("verify_type", VerifyType.EMAIL_OTP.serialName)
                },
            ),
        )
    }

    private suspend fun <T> withToken(
        method: HttpMethod,
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
        handle: suspend (ZetaHttpResponse) -> T,
    ): T {
        suspend fun attempt(): T {
            val url = baseUrl.trimEnd('/') + path
            val accessToken = tokenProvider.getAccessToken(CLIENT_MANAGEMENT_SCOPE)
            val dpopProof = dpopProvider.createDpopProof(method.value, url, accessToken)
            val response = httpClient.request(url) {
                this.method = method
                header(HttpHeaders.Authorization, "${HttpAuthHeaders.Dpop} $accessToken")
                header(HttpAuthHeaders.Dpop, dpopProof)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                configure()
            }
            return handle(response)
        }
        return try {
            attempt()
        } catch (_: ClientManagementUnauthorizedException) {
            tokenProvider.invalidate(CLIENT_MANAGEMENT_SCOPE)
            attempt()
        }
    }

    private suspend fun ZetaHttpResponse.toException(): ClientManagementException {
        val bodyText = runCatching { bodyAsText() }.getOrNull()
        val body = when {
            bodyText == null -> ErrorBody()
            raw.headers[ZETA_ERROR_ORIGIN] == PEP -> ErrorBody(pepError = bodyText.decodeOrNull<PepError>())
            else -> ErrorBody(problem = bodyText.decodeOrNull<ProblemDetails>())
        }
        return when (status) {
            HttpStatusCode.BadRequest -> InvalidClientManagementRequestException(raw, body)
            HttpStatusCode.Unauthorized -> ClientManagementUnauthorizedException(raw, body)
            HttpStatusCode.Forbidden -> ClientManagementForbiddenException(raw, body)
            HttpStatusCode.NotFound -> ClientNotFoundException(raw, body)
            HttpStatusCode.Conflict -> ClientManagementConflictException(raw, body)
            else -> UnexpectedClientManagementException(raw, status.value, body, bodyText?.ifBlank { null })
        }
    }

    private inline fun <reified T> String.decodeOrNull(): T? = runCatching { errorJson.decodeFromString<T>(this) }.getOrNull()

    private companion object {
        const val CLIENTS_PATH = "/clients"
        const val ZETA_ERROR_ORIGIN = "zeta-error-origin"
        const val PEP = "pep"
        val CLIENT_MANAGEMENT_SCOPE = setOf(ClientManagementScopes.CLIENT_MANAGEMENT)
        val json = Json { ignoreUnknownKeys = true }

        // A problem `code` outside the schema enum becomes null instead of losing the whole body.
        val errorJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        val VerifyType.serialName: String get() = VerifyType.serializer().descriptor.getElementName(ordinal)
    }
}
