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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url

/**
 * Contract for retrieving the well-known configuration documents and schemas.
 */
interface ConfigurationApi {
    suspend fun fetchResourceMetadata(resourceUrl: String): String
    suspend fun fetchAuthorizationMetadata(authFqdns: String): String
    suspend fun getResourceSchema(): String
    suspend fun getAuthorizationSchema(): String
}

/**
 * API for retrieving *well-known* configuration documents and their schemas.
 *
 * It also exposes helpers to load the corresponding JSON Schema files that are bundled
 * with the application via the platform-specific [loadResource].
 *
 */
@Suppress("UnusedPrivateProperty")
class ConfigurationApiImpl(
    private val httpClientBuilder: ZetaHttpClientBuilder,
) : ConfigurationApi {

    companion object {
        private const val PROTECTED_RESOURCE_PATH = "oauth-protected-resource"
        private const val AUTH_SERVER_PATH = "oauth-authorization-server"
    }

    /**
     * Fetches the *Protected Resource* well-known document.
     *
     * GET `/.well-known/oauth-protected-resource`
     *
     * @return The raw JSON payload as a UTF-8 text [String].
     */
    override suspend fun fetchResourceMetadata(resourceUrl: String): String {
        val baseUrl = protectedBaseUrl(resourceUrl)

        val response = httpClientBuilder.build(baseUrl)
            .get(PROTECTED_RESOURCE_PATH)

        return handleResponse(resourceUrl + PROTECTED_RESOURCE_PATH, response)
    }

    /**
     * Fetches the *Authorization Server* well-known document.
     *
     * GET `/.well-known/oauth-authorization-server`
     *
     * @return The raw JSON payload as a [String].
     */
    override suspend fun fetchAuthorizationMetadata(authFqdns: String): String {
        val baseUrl = protectedBaseUrl(authFqdns)

        val response = httpClientBuilder.build(baseUrl)
            .get(AUTH_SERVER_PATH)

        return handleResponse(authFqdns + AUTH_SERVER_PATH, response)
    }

    /**
     * Loads the JSON Schema that validates the *Authorization Server* well-known document.
     *
     * @return The schema JSON as a [String].
     */
    override suspend fun getAuthorizationSchema(): String =
        loadResource("as-well-known.json")

    /**
     * Loads the JSON Schema that validates the *Protected Resource* well-known document.
     *
     * @return The schema JSON as a [String].
     */
    override suspend fun getResourceSchema(): String =
        loadResource("opr-well-known.json")
}

private fun protectedBaseUrl(resourceUrl: String): String {
    val u = Url(resourceUrl)
    val defaultPort = when (u.protocol.name.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> 0
    }
    val hostPart = if (':' in u.host) "[${u.host}]" else u.host
    val portPart = if (u.port > 0 && u.port != defaultPort) ":${u.port}" else ""

    return "https://$hostPart$portPart/.well-known/"
}

private suspend fun handleResponse(resourceUrl: String, response: ZetaHttpResponse): String {
    return when (response.status) {
        HttpStatusCode.OK -> {
            response.bodyAsText()
        }

        else -> {
            throw ServiceDiscoveryException(response.raw, "Service discovery failed to load resource: $resourceUrl")
        }
    }
}

/**
 * Loads a text resource packaged with the application.
 *
 * This is declared as `expect` in common code and must be provided by each platform
 *
 * @param fileName File name of the resource (e.g., `"as-well-known.json"`).
 * @return File contents as a [String].
 * @throws IllegalStateException if the resource cannot be found or read (platform-dependent).
 */
expect fun loadResource(fileName: String): String

class ServiceDiscoveryException(
    val response: HttpResponse,
    message: String,
) : Exception(message)
