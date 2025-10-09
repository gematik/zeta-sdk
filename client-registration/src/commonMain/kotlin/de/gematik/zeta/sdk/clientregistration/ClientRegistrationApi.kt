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
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.clientregistration.model.SoftwareClientRegistrationRequest
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

interface ClientRegistrationApi {
    public suspend fun register(request: SoftwareClientRegistrationRequest): ClientRegistrationResponse
}

public class ClientRegistrationApiImpl(
    private val authUrl: String,
) : ClientRegistrationApi {
    // TODO: get it from well known
    private val clientRegistrationEndpoint =
        authUrl + "clients-registrations/openid-connect/"

    override suspend fun register(request: SoftwareClientRegistrationRequest): ClientRegistrationResponse {
        Log.d { "Client registration will proceed" }
        val client = ZetaHttpClientBuilder(clientRegistrationEndpoint).build()
        val response = client.post(clientRegistrationEndpoint) {
            setBody(request)
        }

        // [A_27799](06): Handle registration response
        return handleResponse(response)
    }

    private suspend fun handleResponse(response: HttpResponse): ClientRegistrationResponse {
        return if (response.status == HttpStatusCode.Created) {
            Log.d { "Registration succeeded" }
            response.body()
        } else {
            Log.e { "Registration failed: $response" }
            error("Registration failed: ${response.status}")
        }
    }
}
