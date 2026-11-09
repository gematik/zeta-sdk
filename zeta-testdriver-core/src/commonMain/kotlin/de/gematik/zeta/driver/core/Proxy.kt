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

package de.gematik.zeta.driver.core

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.content.ByteArrayContent
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.queryString
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

public const val POPP_TOKEN_HEADER_NAME: String = "popp"

/** Settings of the forwarding proxy, independent of how a driver stores its own configuration. */
public data class ProxyConfig(
    /** Base URL of the Fachdienst; the `{path...}` of the incoming request is appended to it. */
    val targetBaseUrl: String,
    /** PoPP token added as `popp` header when the incoming request does not carry one. */
    val poppToken: String? = null,
    /** Drop Host / Forwarded / X-Forwarded-* headers in both directions. */
    val filterHostHeaders: Boolean = false,
    val disableTlsVerification: Boolean = false,
    val logLevel: LogLevel = LogLevel.INFO,
)

/** What the proxy needs from a driver at request time. */
public data class ProxyTarget(
    val sdk: ZetaSdkClient,
    val httpClient: ZetaHttpClient,
    val config: ProxyConfig,
)

/**
 * Registers HTTP forwarding under `$prefix/{path...}`.
 *
 * [target] is called for every request, so a driver that rebuilds its SDK or HTTP client
 * (e.g. after `/configure` or `/reset`) is always forwarded with the current instance.
 */
public fun Route.proxyRoutes(prefix: String = "/proxy", target: () -> ProxyTarget) {
    route("$prefix/{path...}") {
        handle {
            forward(call, target())
        }
    }
}

public suspend fun forward(call: ApplicationCall, target: ProxyTarget) {
    val config = target.config
    val targetUrl = buildTargetUrl(config.targetBaseUrl, call.pathSegments(), call.request.queryString())
    val requestBody = extractRequestBody(call)
    val forwardStart = TimeSource.Monotonic.markNow()

    try {
        val (response, httpTime) = measureTimedValue {
            executeHttpRequest(target.httpClient, targetUrl, call, requestBody, config.poppToken?.ifBlank { null }, config.filterHostHeaders)
        }
        Log.i { "[TESTDRIVER-FORWARD-TIMING] url=$targetUrl method=${call.request.httpMethod.value} http_request=$httpTime status=${response.status}" }

        val (bytes, bodyReadTime) = measureTimedValue { response.body<ByteArray>() }
        Log.i { "[TESTDRIVER-FORWARD-TIMING] url=$targetUrl body_read=$bodyReadTime body_size=${bytes.size}" }

        forwardResponse(call, response, bytes, config.filterHostHeaders)
        Log.i { "[TESTDRIVER-DRIVER-FORWARD-TIMING] url=$targetUrl total=${forwardStart.elapsedNow()}" }
    } catch (ex: Throwable) {
        Log.i { "[TESTDRIVER-DRIVER-FORWARD-TIMING] url=$targetUrl FAILED in ${forwardStart.elapsedNow()}: ${ex.message}" }
        call.respondText(
            ex.message ?: "Unexpected error while forwarding request",
            status = HttpStatusCode.InternalServerError,
        )
    }
}

private val notForwardedHeaders = setOf(
    HttpHeaders.ContentType,
    HttpHeaders.ContentLength,
    HttpHeaders.TransferEncoding,
    HttpHeaders.Connection,
)

private val optionallyNotForwardedHeaders = setOf(
    HttpHeaders.Host,
    HttpHeaders.Forwarded,
    HttpHeaders.XForwardedHost,
    HttpHeaders.XForwardedPort,
)

public fun shouldForwardHeader(name: String, filterHostHeaders: Boolean): Boolean =
    notForwardedHeaders.none { it.equals(name, ignoreCase = true) } &&
        (!filterHostHeaders || optionallyNotForwardedHeaders.none { it.equals(name, ignoreCase = true) })

public fun buildForwardHeaders(
    incomingHeaders: Headers,
    poppToken: String?,
    filterHostHeaders: Boolean,
): Headers = Headers.build {
    incomingHeaders.forEach { name, values ->
        if (shouldForwardHeader(name, filterHostHeaders)) appendAll(name, values)
    }
    if (!incomingHeaders.contains(POPP_TOKEN_HEADER_NAME)) {
        poppToken?.let { append(POPP_TOKEN_HEADER_NAME, it) }
    }
}

/** `base` + `/` + path segments, plus the original query string (if any). */
public fun buildTargetUrl(baseUrl: String, pathSegments: List<String>, queryString: String): String {
    val url = "${baseUrl.trimEnd('/')}/${pathSegments.joinToString("/")}"
    return if (queryString.isEmpty()) url else "$url?$queryString"
}

private fun ApplicationCall.pathSegments(): List<String> = parameters.getAll("path").orEmpty()

private suspend fun extractRequestBody(call: ApplicationCall): ByteArray? {
    val hasBody = call.request.headers.contains(HttpHeaders.ContentType) ||
        call.request.headers.contains(HttpHeaders.TransferEncoding)
    return if (hasBody) call.request.receiveChannel().toByteArray() else null
}

private suspend fun executeHttpRequest(
    httpClient: ZetaHttpClient,
    targetUrl: String,
    call: ApplicationCall,
    requestBody: ByteArray?,
    poppToken: String?,
    filterHostHeaders: Boolean,
): ZetaHttpResponse {
    val forwardHeaders = buildForwardHeaders(call.request.headers, poppToken, filterHostHeaders)

    return httpClient.request(targetUrl) {
        method = call.request.httpMethod
        headers { appendAll(forwardHeaders) }
        if (requestBody != null) setRequestBody(this, call, requestBody)
    }
}

private fun setRequestBody(builder: HttpRequestBuilder, call: ApplicationCall, requestBody: ByteArray) {
    val contentType = call.request.headers[HttpHeaders.ContentType]
    if (contentType != null) {
        builder.setBody(ByteArrayContent(requestBody, ContentType.parse(contentType)))
    } else {
        builder.setBody(requestBody)
    }
}

private suspend fun forwardResponse(
    call: ApplicationCall,
    response: ZetaHttpResponse,
    bytes: ByteArray,
    filterHostHeaders: Boolean,
) {
    response.headers.forEach { (name, value) ->
        if (shouldForwardHeader(name, filterHostHeaders)) call.response.headers.append(name, value)
    }

    val contentType = response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
    if (contentType != null) {
        call.respondBytes(bytes, contentType = contentType, status = response.status)
    } else {
        call.respondBytes(bytes, status = response.status)
    }
}
