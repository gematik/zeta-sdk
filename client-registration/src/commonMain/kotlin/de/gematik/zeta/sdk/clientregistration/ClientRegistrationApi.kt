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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

interface ClientRegistrationApi {
    public suspend fun register(endpoint: String, request: ClientRegistrationRequest): ClientRegistrationResponse
}

public class ClientRegistrationApiImpl(
    private val httpClientBuilder: ZetaHttpClientBuilder,
) : ClientRegistrationApi {

    override suspend fun register(endpoint: String, request: ClientRegistrationRequest): ClientRegistrationResponse {
        Log.d { "Client registration will proceed" }

        val client = httpClientBuilder.build(endpoint)

        val response = client.post("") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // [A_27799](06): Handle registration response
        // TODO: remove also when registration endpoint works.
        return handleResponse(response)
    }

    private suspend fun handleResponse(response: ZetaHttpResponse): ClientRegistrationResponse {
        return if (response.status == HttpStatusCode.Created) {
            Log.d { "Registration succeeded" }
            response.body()
        } else {
            Log.e { "Registration failed: $response" }
            error("Registration failed: ${response.status}")
        }
    }
}
