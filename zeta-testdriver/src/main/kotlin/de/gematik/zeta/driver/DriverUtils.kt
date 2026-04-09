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

package de.gematik.zeta.driver

import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.content.ByteArrayContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.headers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.utils.io.toByteArray
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.lang.System
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

internal const val DISABLE_SERVER_VALIDATION = "DISABLE_SERVER_VALIDATION"
internal const val POPP_TOKEN_HEADER_NAME = "PoPP"

internal val logger: Logger = object : Logger {
    override fun log(message: String) = println(message)
}

internal fun shouldForwardHeader(name: String): Boolean {
    return notForwardedHeaders.none { it.equals(name, ignoreCase = true) }
}

private val notForwardedHeaders = setOf(
    HttpHeaders.ContentType,
    HttpHeaders.ContentLength,
    HttpHeaders.TransferEncoding,
    HttpHeaders.Connection,
)

internal suspend fun forward(
    call: ApplicationCall,
    httpClient: ZetaHttpClient,
    config: SdkInstanceConfig,
) {
    val fachdienstUrl = requireNotNull(config.fachdienstUrl) { "fachdienstUrl is required" }
    val targetUrl = buildTargetUrl(call, fachdienstUrl)
    val requestBody = extractRequestBody(call)
    val hasPopp = call.request.headers.contains(POPP_TOKEN_HEADER_NAME)
    val forwardStart = TimeSource.Monotonic.markNow()

    try {
        val (response, httpTime) = measureTimedValue {
            executeHttpRequest(httpClient, targetUrl, call, requestBody, hasPopp, config.poppToken)
        }

        Log.i { "[TESTDRIVER-FORWARD-TIMING] url=$targetUrl method=${call.request.httpMethod.value} http_request=$httpTime status=${response.status}" }
        val (bytes, bodyReadTime) = measureTimedValue { response.body<ByteArray>() }
        Log.i { "[TESTDRIVER-FORWARD-TIMING] url=$targetUrl body_read=$bodyReadTime body_size=${bytes.size}" }

        forwardResponse(call, response, bytes)

        Log.i { "[TESTDRIVER-DRIVER-FORWARD-TIMING] url=$targetUrl total=${forwardStart.elapsedNow()}" }
    } catch (ex: Throwable) {
        Log.i { "[TESTDRIVER-DRIVER-FORWARD-TIMING] url=$targetUrl FAILED in ${forwardStart.elapsedNow()}: ${ex.message}" }
        call.respondText(
            ex.message ?: "Unexpected error while forwarding request",
            status = HttpStatusCode.InternalServerError,
        )
    }
}

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
    hasPopp: Boolean,
    poppToken: String?,
): ZetaHttpResponse {
    return httpClient.request(targetUrl) {
        method = call.request.httpMethod
        headers {
            call.request.headers.forEach { name, values ->
                if (shouldForwardHeader(name)) headers.appendAll(name, values)
            }
            if (!hasPopp) {
                poppToken?.let { headers.append(POPP_TOKEN_HEADER_NAME, it) }
            }
        }
        if (requestBody != null) {
            setRequestBody(this, call, requestBody)
        }
    }
}

private fun setRequestBody(
    builder: HttpRequestBuilder,
    call: ApplicationCall,
    requestBody: ByteArray,
) {
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
) {
    response.headers.forEach { (name, value) ->
        if (shouldForwardHeader(name)) call.response.headers.append(name, value)
    }

    val contentType = response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
    if (contentType != null) {
        call.respondBytes(bytes, contentType = contentType, status = response.status)
    } else {
        call.respondBytes(bytes, status = response.status)
    }
}

internal fun buildTargetUrl(call: ApplicationCall, fachdienstUrl: String): String {
    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
    return "${fachdienstUrl.trimEnd('/')}/$path"
}

public suspend fun forwardWs(
    serverSession: DefaultWebSocketSession,
    sdk: ZetaSdkClient,
    targetUrl: String,
    config: SdkInstanceConfig,
): Unit = coroutineScope {
    val customHeaders = wsCustomHeaders(config.poppToken)

    sdk.ws(targetUrl, wsClientConfig(), customHeaders) {
        val backendSession = this

        val clientToBackend = launch { forwardFrames(serverSession, backendSession) }
        val backendToClient = launch { forwardFrames(backendSession, serverSession) }

        clientToBackend.join()
        backendToClient.join()
    }
}

private fun wsCustomHeaders(poppToken: String?): Map<String, String> {
    val headers = mutableMapOf<String, String>()

    if (!headers.containsKey(POPP_TOKEN_HEADER_NAME)) {
        poppToken?.let { headers[POPP_TOKEN_HEADER_NAME] = it }
    }

    return headers
}

public fun buildWsTargetUrl(
    call: ApplicationCall,
    config: SdkInstanceConfig,
    prefixToRemove: String = "/proxy",
): String {
    val fachdienstUrl = requireNotNull(config.fachdienstUrl) { "fachdienstUrl is required" }
    val base = Url(fachdienstUrl)
    val afterProxy = call
        .request
        .path()
        .removePrefix(prefixToRemove)
        .trimStart('/')

    val wsProtocol = when (base.protocol) {
        URLProtocol.HTTPS -> URLProtocol.WSS
        URLProtocol.HTTP -> URLProtocol.WS
        else -> base.protocol
    }
    val builder = URLBuilder().apply {
        protocol = wsProtocol
        host = base.host
        port = base.port
        encodedPath = buildString {
            append(base.encodedPath.trimEnd('/'))
            if (afterProxy.isNotEmpty()) {
                append('/')
                append(afterProxy)
            }
        }
    }
    return builder.buildString()
}

private fun wsClientConfig(): ZetaHttpClientBuilder.() -> Unit = {
    logging(LogLevel.ALL, logger)
    disableServerValidation("true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()))
}

private suspend fun forwardFrames(
    from: DefaultWebSocketSession,
    to: DefaultWebSocketSession,
) {
    for (frame in from.incoming) {
        when (frame) {
            is Frame.Text -> to.send(Frame.Text(frame.readText()))

            is Frame.Binary -> to.send(Frame.Binary(true, frame.readBytes()))

            is Frame.Close -> {
                to.send(Frame.Close(frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "Closed")))
                return
            }

            else -> Unit
        }
    }
}

public fun newSdk(storage: SdkStorage, config: SdkInstanceConfig): ZetaSdkClient {
    val fachdienstUrl = requireNotNull(config.fachdienstUrl) { "fachdienstUrl is required" }

    return ZetaSdk.build(
        resource = fachdienstUrl,
        BuildConfig(
            "test-proxy",
            "0.5.0",
            "sdk-client",
            StorageConfig(storage),
            object : TpmConfig {},
            AuthConfig(
                listOf("zero:audience"),
                30,
                aslProdEnvironment = config.aslProdEnv,
                when {
                    config.smbKeystoreB64.isNotEmpty() ->
                        SmbTokenProvider(
                            SmbTokenProvider.Credentials(
                                keystoreB64 = config.smbKeystoreB64,
                                alias = config.smbKeystoreAlias,
                                password = config.smbKeystorePassword,
                            ),
                        )

                    config.smbKeystoreFile.isNotEmpty() ->
                        SmbTokenProvider(
                            SmbTokenProvider.Credentials(
                                config.smbKeystoreFile,
                                config.smbKeystoreAlias,
                                config.smbKeystorePassword,
                            ),
                        )

                    config.smcbBaseUrl.isNotEmpty() ->
                        SmcbTokenProvider(
                            SmcbTokenProvider.ConnectorConfig(
                                config.smcbBaseUrl,
                                config.smcbMandantId,
                                config.smcbClientSystemId,
                                config.smcbWorkspaceId,
                                config.smcbUserId,
                                config.smcbCardHandle,
                            ),
                        )

                    else ->
                        error("No SM-B or SMC-B configuration was provided")
                },
            ),
            platformProductId = getPlatformProduct(),
            ZetaHttpClientBuilder()
                .timeouts(20000, 20000)
                .disableServerValidation("true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()))
                .logging(LogLevel.ALL, logger)
                .apply {
                    customCaPems.forEach { pem ->
                        addCaPem(pem)
                    }
                },
        ),
    )
}

private fun getPlatformProduct(): PlatformProductId {
    return when (val plat = platform()) {
        is Platform.Jvm.Macos, Platform.Native.Macos -> PlatformProductId.AppleProductId("apple", "macos", listOf())
        is Platform.Jvm.Linux -> PlatformProductId.LinuxProductId("linux", "", "demo-client", "0.5.0")
        is Platform.Jvm.Windows -> PlatformProductId.WindowsProductId("windows", "", "demo-client")
        else -> error("Unknown platform: $plat")
    }
}

public suspend fun reset(
    call: ApplicationCall,
    sdkClient: ZetaSdkClient,
) {
    try {
        sdkClient.forget()

        call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun authenticate(
    call: ApplicationCall,
    sdk: ZetaSdkClient,
) {
    try {
        val result = sdk.authenticate()
        if (result.isSuccess) {
            call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
        } else {
            call.respond(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        }
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun discover(
    call: ApplicationCall,
    sdk: ZetaSdkClient,
) {
    try {
        val result = sdk.discover()
        if (result.isSuccess) {
            call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
        } else {
            call.respond(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        }
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun register(
    call: ApplicationCall,
    sdk: ZetaSdkClient,
) {
    try {
        val result = sdk.register()
        if (result.isSuccess) {
            call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
        } else {
            call.respond(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        }
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}
