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

package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.config.ClientConfig
import de.gematik.zeta.sdk.network.http.client.config.IDEMPOTENT_METHODS
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.core.Closeable
import kotlinx.serialization.json.Json

/**
 * Builds a preconfigured [HttpClient] using the provided [ClientConfig] DSL.
 *
 * This function wires:
 *  - Base URL override via [DefaultRequest] (if provided).
 *  - Timeouts via [HttpTimeout].
 *  - Retries via [HttpRequestRetry] with exponential backoff when [ClientConfig.network.maxRetries] > 0.
 *    * Retries on HTTP responses whose status is in [ClientConfig.network.retryStatusCodes].
 *    * Retries on exceptions for requests whose method is idempotent (or always, if
 *      `retryOnlyIdempotent=false`).
 *  - Logging via [Logging] when [ClientConfig.monitoring.logLevel] != [LogLevel.NONE].
 *  - JSON (kotlinx.serialization) via [ContentNegotiation].
 *
 * If an engine factory is injected in [ClientConfig.engineFactory], it is used; otherwise, a
 * platform-appropriate engine is created by [de.gematik.zeta.sdk.network.http.client.buildPlatformClient].
 *
 * @param configure Lambda to mutate a fresh [ClientConfig].
 * @param addExtras Lambda to add extra custom configuration.
 * @return A ready-to-use Ktor [HttpClient].
 */
internal fun zetaHttpClient(
    configure: ClientConfig.() -> Unit,
    addExtras: (HttpClientConfig<*>.() -> Unit)? = null,
): ZetaHttpClient {
    Log.d { "Configuring the HTTP client" }
    val cfg = ClientConfig().apply(configure)

    Log.i { "Disable server validation = " + cfg.security.disableServerValidation }

    val commonSetup: HttpClientConfig<*>.() -> Unit = {
        install(DefaultRequest) {
            url {
                takeFrom(cfg.baseUrlOverride)
            }
        }

        install(HttpTimeout) {
            Log.d { "Setting up the connection and request timeouts" }
            connectTimeoutMillis = cfg.network.connectionTimeoutMillis
            requestTimeoutMillis = cfg.network.requestTimeoutMillis
        }

        if (cfg.network.maxRetries > 0) {
            Log.d { "Setting up the request retry plugin" }
            install(HttpRequestRetry) {
                maxRetries = cfg.network.maxRetries

                retryIf { request, response ->
                    val methodOk = !cfg.network.retryOnlyIdempotent || request.method in IDEMPOTENT_METHODS
                    methodOk && response.status in cfg.network.retryStatusCodes
                }

                retryOnExceptionIf { request, _ ->
                    !cfg.network.retryOnlyIdempotent || request.method in IDEMPOTENT_METHODS
                }

                Log.d { "Setting up exponential backoff" }
                exponentialDelay()
            }
        }

        if (cfg.monitoring.logLevel != LogLevel.NONE) {
            install(Logging) {
                level = cfg.monitoring.logLevel
                logger = cfg.monitoring.logProvider
            }
        }

        install(ContentNegotiation) {
            Log.d { "Installing ContentNegotiation JSON plugin" }
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }

        addExtras?.invoke(this)
    }

    val injected = cfg.engineFactory
    val httpClient = if (injected != null) {
        HttpClient(injected()) { commonSetup(this) }
    } else {
        buildPlatformClient(cfg, commonSetup)
    }

    return ZetaHttpClient(httpClient)
}

/**
 * Creates an [HttpClient] with a platform-specific engine and applies [commonSetup].
 *
 * Implement this in each target (e.g., JVM/Android with CIO/OkHttp, iOS with OpenSSL).
 * Responsibilities typically include:
 *  - Applying cfg.security.additionalCaPem to the engine trust manager (if supported on platform).
 *  - Choosing sensible engine defaults for the platform.
 *
 * @param cfg          The finalized client configuration.
 * @param commonSetup  Shared Ktor configuration to apply to the client.
 */
internal expect fun buildPlatformClient(
    cfg: ClientConfig,
    commonSetup: HttpClientConfig<*>.() -> Unit,
): HttpClient

/** Normalizes a URL/host to a lowercase FQDN key. */
public fun hostOf(value: String): String {
    val s = if ("://" in value || value.startsWith("//")) value else "https://$value"
    return Url(s).host.trim().trimEnd('.').lowercase()
}

public class ZetaHttpClient public constructor(
    public val delegate: HttpClient,
) : Closeable {

    override fun close() {
        delegate.close()
    }

    public inline fun <R> useRaw(block: HttpClient.() -> R): R = delegate.block()

    public suspend inline fun get(
        urlString: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.get(urlString, block)
        return response.toZetaResponse()
    }

    public suspend inline fun get(
        url: Url,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.get(url, block)
        return response.toZetaResponse()
    }

    public suspend inline fun post(
        urlString: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.post(urlString, block)
        return response.toZetaResponse()
    }

    public suspend inline fun post(
        url: Url,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.post(url, block)
        return response.toZetaResponse()
    }

    public suspend inline fun put(
        urlString: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.put(urlString, block)
        return response.toZetaResponse()
    }

    public suspend inline fun put(
        url: Url,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.put(url, block)
        return response.toZetaResponse()
    }

    public suspend inline fun delete(
        urlString: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.delete(urlString, block)
        return response.toZetaResponse()
    }

    public suspend inline fun <reified T> delete(
        url: Url,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.delete(url, block)
        return response.toZetaResponse()
    }

    public suspend inline fun patch(
        urlString: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.patch(urlString, block)
        return response.toZetaResponse()
    }

    public suspend inline fun patch(
        url: Url,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.patch(url, block)
        return response.toZetaResponse()
    }

    public suspend inline fun request(
        noinline block: HttpRequestBuilder.() -> Unit,
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.request(block)
        return response.toZetaResponse()
    }

    public suspend inline fun request(
        urlString: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.request {
            url(urlString)
            block()
        }
        return response.toZetaResponse()
    }

    public suspend inline fun request(
        url: Url,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.request {
            url(url)
            block()
        }
        return response.toZetaResponse()
    }

    public suspend inline fun submitForm(
        urlString: String,
        formParameters: Parameters,
        encodeInQuery: Boolean = false,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): ZetaHttpResponse {
        val response: HttpResponse = delegate.submitForm(
            url = urlString,
            formParameters = formParameters,
            encodeInQuery = encodeInQuery,
            block = block,
        )
        return response.toZetaResponse()
    }

    public suspend fun webSocket(request: HttpRequestBuilder.() -> Unit, block: suspend DefaultClientWebSocketSession.() -> Unit) {
        return delegate.webSocket(request, block)
    }
}
