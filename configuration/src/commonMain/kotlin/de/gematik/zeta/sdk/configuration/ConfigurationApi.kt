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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.sdk.configuration.models.OidcDiscoveryResponse
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

interface ConfigurationApi {
    suspend fun fetchResourceMetadata(): String
    suspend fun fetchAuthorizationMetadata(): OidcDiscoveryResponse
    suspend fun getResourceSchema(): String
    suspend fun getAuthorizationSchema(): String
}

/*
 * Note that the baseAsWellKnownUrl will be going away in further releases,
 * once configuration and discovery is fully implemented
 */
@Suppress("UnusedPrivateProperty")
public class ConfigurationApiImpl(
    private val resource: String,
    // temporary until fully implemented
    private val baseAsWellKnownUrl: String,
) : ConfigurationApi {
    private val baseUrl: String = "$baseAsWellKnownUrl/.well-known/"

    override suspend fun fetchResourceMetadata(): String {
        val client = ZetaHttpClientBuilder(baseUrl).build()
        return client
            .get("oauth-protected-resource")
            .bodyAsText()
    }

    override suspend fun fetchAuthorizationMetadata(): OidcDiscoveryResponse {
        val client = ZetaHttpClientBuilder(baseUrl).build()

        return client
            .get("oauth-authorization-server")
            .body()
    }

    override suspend fun getResourceSchema(): String {
        return loadResource("as-well-known.json")
    }

    override suspend fun getAuthorizationSchema(): String {
        return loadResource("opr-well-known.json")
    }
}

expect fun loadResource(fileName: String): String
