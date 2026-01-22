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

package de.gematik.zeta.sdk.clientregistration

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

interface ClientRegistrationApi {
    suspend fun register(endpoint: String, request: ClientRegistrationRequest): ClientRegistrationResponse
}

class ClientRegistrationApiImpl(
    private val zetaHttpClient: ZetaHttpClient,
) : ClientRegistrationApi {

    override suspend fun register(endpoint: String, request: ClientRegistrationRequest): ClientRegistrationResponse {
        Log.d { "Client registration will proceed" }

        val response = zetaHttpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // [A_27799](06): Handle registration response
        return handleResponse(request, response)
    }

    private suspend fun handleResponse(request: ClientRegistrationRequest, response: ZetaHttpResponse): ClientRegistrationResponse {
        return when (response.status) {
            HttpStatusCode.Created -> {
                Log.d { "Registration for client ${request.clientName} succeeded" }
                response.body()
            }

            HttpStatusCode.BadRequest -> {
                Log.e { "Error while registering client: ${request.clientName}" }
                throw ClientRegistrationException(response.raw, "Invalid client data")
            }

            HttpStatusCode.Unauthorized -> {
                Log.e { "Authentication failed for client: ${request.clientName}" }
                throw ClientRegistrationException(response.raw, "Client authentication failed")
            }

            HttpStatusCode.Forbidden -> {
                Log.e { "Authentication failed for client: ${request.clientName}" }
                throw ClientRegistrationException(response.raw, "Registration denied for client: ${request.clientName} ")
            }

            HttpStatusCode.Conflict -> {
                Log.e { "Authentication failed for client: ${request.clientName}" }
                throw ClientRegistrationException(response.raw, "Client is already registered: ${request.clientName} ")
            }

            else -> {
                Log.e { "Unexpected registration error for client: ${request.clientName}. Error: ${response.status.description}" }
                throw ClientRegistrationException(response.raw, "Unexpected registration error: ${response.status.description}")
            }
        }
    }
}

class ClientRegistrationException(
    val response: HttpResponse,
    message: String,
) : Throwable(message)
