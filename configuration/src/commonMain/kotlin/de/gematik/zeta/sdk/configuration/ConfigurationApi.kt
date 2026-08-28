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
import de.gematik.zeta.sdk.configuration.models.AUTHORIZATION_SERVER_SCHEMA_JSON
import de.gematik.zeta.sdk.configuration.models.PROTECTED_RESOURCE_SCHEMA_JSON
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url

/**
 * Contract for retrieving the well-known configuration documents and schemas.
 */
interface ConfigurationApi {
    /**
     * Fetches a *Protected Resource* well-known document. A non-null [subpath] selects the
     * metadata of a co-deployed resource published under a well-known subpath (RFC 9728 /
     * A_28436), e.g. `/.well-known/oauth-protected-resource/notification-service`.
     */
    suspend fun fetchResourceMetadata(resourceUrl: String, subpath: String? = null, eTag: String? = null): DiscoveryFetchResult
    suspend fun fetchAuthorizationMetadata(authFqdns: String, eTag: String? = null): DiscoveryFetchResult
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
     * GET `/.well-known/oauth-protected-resource[/subpath]`
     *
     * @return The raw JSON payload as a UTF-8 text [String].
     */
    override suspend fun fetchResourceMetadata(resourceUrl: String, subpath: String?, eTag: String?): DiscoveryFetchResult {
        return try {
            Log.i { "[ZETA-SDK] fetchResourceMetadata get base Url for: $resourceUrl" }
            val baseUrl = protectedBaseUrl(resourceUrl)
            val path = if (subpath.isNullOrBlank()) {
                PROTECTED_RESOURCE_PATH
            } else {
                "$PROTECTED_RESOURCE_PATH/${subpath.trim('/')}"
            }
            Log.i { "[ZETA-SDK] fetchResourceMetadata http get base Url for: $path" }
            val client = httpClientBuilder.build(baseUrl)
            try {
                val response = client.get(path) {
                    eTag?.let {
                        header(HttpHeaders.IfNoneMatch, it)
                    }
                }
                handleResponse(baseUrl + path, response)
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Log.e { "[ZETA-SDK] fetchResourceMetadata failed: ${e::class.simpleName} - ${e.message}" }
            throw e
        }
    }

    /**
     * Fetches the *Authorization Server* well-known document.
     *
     * GET `/.well-known/oauth-authorization-server`
     *
     * @return The raw JSON payload as a [String].
     */
    override suspend fun fetchAuthorizationMetadata(authFqdns: String, eTag: String?): DiscoveryFetchResult {
        val baseUrl = protectedBaseUrl(authFqdns)
        Log.i { "[ZETA-SDK] fetchAuthorizationMetadata http get base Url for: $AUTH_SERVER_PATH" }
        return try {
            val client = httpClientBuilder.build(baseUrl)
            try {
                val response = client.get(AUTH_SERVER_PATH) {
                    eTag?.let {
                        header(HttpHeaders.IfNoneMatch, it)
                    }
                }

                handleResponse(baseUrl + AUTH_SERVER_PATH, response)
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Log.e { "[ZETA-SDK] fetchAuthorizationMetadata failed: ${e::class.simpleName} - ${e.message}" }
            throw e
        }
    }

    /**
     * Loads the JSON Schema that validates the *Authorization Server* well-known document.
     *
     * @return The schema JSON as a [String].
     */
    override suspend fun getAuthorizationSchema(): String =
        AUTHORIZATION_SERVER_SCHEMA_JSON

    /**
     * Loads the JSON Schema that validates the *Protected Resource* well-known document.
     *
     * @return The schema JSON as a [String].
     */
    override suspend fun getResourceSchema(): String =
        PROTECTED_RESOURCE_SCHEMA_JSON
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

private suspend fun handleResponse(resourceUrl: String, response: ZetaHttpResponse): DiscoveryFetchResult {
    return when (response.status) {
        HttpStatusCode.OK -> {
            Log.i { "[ZETA-SDK] handleResponse OK: ${response.status}" }
            DiscoveryFetchResult(
                body = response.bodyAsText(),
                maxAgeSeconds = parseMaxAge(response.raw.headers[HttpHeaders.CacheControl.lowercase()]),
                eTag = response.raw.headers[HttpHeaders.ETag.lowercase()],
                notModified = false,
            )
        }

        HttpStatusCode.NotModified -> {
            DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = parseMaxAge(
                    response.raw.headers[HttpHeaders.CacheControl],
                ),
                eTag = response.raw.headers[HttpHeaders.ETag.lowercase()],
                notModified = true,
            )
        }

        else -> {
            throw ServiceDiscoveryException(response.raw, "Service discovery failed to load resource: $resourceUrl")
        }
    }
}

private fun parseMaxAge(cacheControl: String?): Long? =
    cacheControl
        ?.split(',')
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("max-age=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()

class ServiceDiscoveryException(
    val response: HttpResponse,
    message: String,
) : Exception(message)

data class DiscoveryFetchResult(
    val body: String?,
    val maxAgeSeconds: Long?,
    val eTag: String?,
    val notModified: Boolean,
)
