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
import de.gematik.zeta.sdk.clientregistration.model.toJson
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

interface ClientRegistrationApi {
    suspend fun register(endpoint: String, request: ClientRegistrationRequest): ClientRegistrationResponse

    /** Fetches a fresh nonce for the proof of possession, unchanged as dcr-request.yaml `definitions/nonce` requires. */
    suspend fun fetchNonce(endpoint: String): String
}

class ClientRegistrationApiImpl(
    private val zetaHttpClient: ZetaHttpClient,
) : ClientRegistrationApi {

    override suspend fun fetchNonce(endpoint: String): String {
        val response = zetaHttpClient.get(endpoint)
        if (response.status != HttpStatusCode.OK) {
            Log.e { "Failed to get a nonce for client registration: ${response.status}" }
            throw ClientRegistrationException(response.raw, "Failed to get nonce: ${response.status.description}")
        }
        return response.bodyAsText().trim()
    }

    override suspend fun register(endpoint: String, request: ClientRegistrationRequest): ClientRegistrationResponse {
        Log.d { "Client registration will proceed" }
        // Abb-ZETA-DCR-für-mobile-Clients (01) / (12) / (15) POST /register
        val response = zetaHttpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(request.toJson())
        }
        return handleResponse(request.metadata.clientName, response)
    }

    private suspend fun handleResponse(clientName: String, response: ZetaHttpResponse): ClientRegistrationResponse {
        return when (response.status) {
            // Abb-ZETA-DCR-für-mobile-Clients (14) / (21) 201 Created {client_id, status}
            HttpStatusCode.Created -> {
                response.body<ClientRegistrationResponse>().also {
                    Log.d { "Registration for client $clientName succeeded: client_id=${it.clientId} status=${it.status}" }
                }
            }

            HttpStatusCode.BadRequest -> {
                Log.e { "Error while registering client: $clientName" }
                throw ClientRegistrationException(response.raw, "Invalid client data")
            }

            HttpStatusCode.Unauthorized -> {
                Log.e { "Authentication failed for client: $clientName" }
                throw ClientRegistrationException(response.raw, "Client authentication failed")
            }

            HttpStatusCode.Forbidden -> {
                Log.e { "Registration denied for client: $clientName" }
                throw ClientRegistrationException(response.raw, "Registration denied for client: $clientName ")
            }

            HttpStatusCode.Conflict -> {
                Log.e { "Client is already registered: $clientName" }
                throw ClientRegistrationException(response.raw, "Client is already registered: $clientName ")
            }

            else -> {
                Log.e { "Unexpected registration error for client: $clientName. Error: ${response.status.description}" }
                throw ClientRegistrationException(response.raw, "Unexpected registration error: ${response.status.description}")
            }
        }
    }
}

open class ClientRegistrationException(
    val response: HttpResponse?,
    message: String,
) : Throwable(message)
