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

import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.ClientSelfAssessment
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.InMemoryStorage
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.setBody
import io.ktor.content.ByteArrayContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.headers
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.appendAll
import io.ktor.utils.io.toByteArray
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.lang.System
import kotlin.sequences.forEach

private val store = InMemoryStorage()
private var testFachDienstUrl: String = ""
private val POPP_TOKEN: String? = System.getenv("POPP_TOKEN")

private const val DISABLE_SERVER_VALIDATION = "DISABLE_SERVER_VALIDATION"

private val logger: Logger = object : Logger {
    override fun log(message: String) = println(message)
}

public fun main() {
    val host = System.getenv("LISTEN_HOST") ?: "0.0.0.0"
    val port = System.getenv("LISTEN_PORT")?.toInt() ?: 8080

    embeddedServer(
        Netty,
        port = port,
        host = host,
        module = Application::module,
    ).start(wait = true)
}

public fun Application.module() {
    install(CallLogging)
    install(WebSockets)
    install(CORS) {
        anyHost()
        HttpMethod.DefaultMethods.forEach { allowMethod(it) }
    }

    routing()
}

public fun Application.routing() {
    val sdk = configureSdk()
    val httpClient = sdk.httpClient {
        logging(
            LogLevel.ALL,
            logger,
        )
        disableServerValidation("true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()))
    }

    routing {
        route("/proxy/{...}") {
            handle {
                forward(call, httpClient)
            }
        }

        webSocket("/proxy/{...}") {
            val targetUrl = buildWsTargetUrl(call)
            forwardWs(this, sdk, targetUrl)
        }

        get("/testdriver-api/authenticate") { authenticate(call, sdk) }
        get("/testdriver-api/discover") { discover(call, sdk) }
        get("/testdriver-api/register") { register(call, sdk) }
        get("/testdriver-api/storage") { storage(call) }
        get("/testdriver-api/reset") { reset(call) }
        get("/health") { call.respondText("alive") }
    }
}

private const val POPP_TOKEN_HEADER_NAME = "PoPP"

private suspend fun forward(
    call: ApplicationCall,
    httpClient: ZetaHttpClient,
) {
    val targetUrl = buildTargetUrl(call.request)
    val hasBody =
        call.request.headers.contains(HttpHeaders.ContentType) ||
            call.request.headers.contains(HttpHeaders.TransferEncoding)

    val requestBody = if (hasBody) call.request.receiveChannel().toByteArray() else null

    // incoming request already has PoPP token?
    val hasPopp = call.request.headers.contains(POPP_TOKEN_HEADER_NAME)

    try {
        val response: ZetaHttpResponse =
            httpClient.request(targetUrl) {
                method = call.request.httpMethod
                headers {
                    call.request.headers
                        .forEach { name, values ->
                            if (shouldForwardHeader(name)) {
                                headers.appendAll(name, values)
                            }
                        }

                    if (!hasPopp) {
                        // only set our configured PoPP token if a) configured and b) is not already in incoming request
                        POPP_TOKEN?.let { headers.append(POPP_TOKEN_HEADER_NAME, it) }
                    }
                }
                if (requestBody != null) {
                    val contentType = call.request.headers[HttpHeaders.ContentType]
                    if (contentType != null) {
                        setBody(ByteArrayContent(requestBody, ContentType.parse(contentType)))
                    } else {
                        setBody(requestBody)
                    }
                }
            }

        val statusCode = response.status
        val contentType = response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
        val bytes: ByteArray = response.body()

        response.headers
            .forEach { (name, value) ->
                if (shouldForwardHeader(name)) {
                    call.response.headers.append(name, value)
                }
            }

        if (contentType != null) {
            call.respondBytes(bytes, contentType = contentType, status = statusCode)
        } else {
            call.respondBytes(bytes, status = statusCode)
        }
    } catch (ex: Throwable) {
        call.respondText(ex.message ?: "Unexpected error while forwarding request", status = HttpStatusCode.InternalServerError)
    }
}

private suspend fun authenticate(
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

private suspend fun discover(
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

private suspend fun register(
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

private fun buildTargetUrl(request: ApplicationRequest): String {
    val path = request.uri
    val forwardedUrl = path
        .removePrefix("/proxy/")

    return forwardedUrl
}

private suspend fun storage(
    call: ApplicationCall,
) {
    try {
        val snapshot = store.map.toList()
        val entries: JsonObject = buildJsonObject {
            snapshot.asSequence()
                .forEach { (key, value) ->
                    val trimmed = value.trim()
                    val element: JsonElement = runCatching {
                        Json.parseToJsonElement(trimmed)
                    }.getOrElse {
                        JsonPrimitive(trimmed)
                    }
                    put(key, element)
                }
        }
        call.respondText(Json.encodeToString(NestedUnquotedJson, entries), ContentType.Application.Json)
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

private suspend fun reset(
    call: ApplicationCall,
) {
    try {
        ZetaSdk.forget()

        call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

private fun buildWsTargetUrl(call: ApplicationCall): String {
    val base = Url(testFachDienstUrl)
    val afterProxy = call
        .request
        .path()
        .removePrefix("/proxy")
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

private suspend fun forwardWs(
    serverSession: DefaultWebSocketSession,
    sdk: ZetaSdkClient,
    targetUrl: String,
) = coroutineScope {
    val customHeaders = wsCustomHeaders()

    sdk.ws(targetUrl, { logging(LogLevel.ALL, logger) }, customHeaders) {
        val backendSession = this

        val client = launch {
            for (frame in serverSession.incoming) {
                when (frame) {
                    is Frame.Text -> backendSession.send(Frame.Text(frame.readText()))

                    is Frame.Binary -> backendSession.send(Frame.Binary(true, frame.readBytes()))

                    is Frame.Close -> {
                        backendSession.send(Frame.Close(frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "Closed")))
                        return@launch
                    }

                    else -> Unit
                }
            }
        }

        val server = launch {
            for (frame in backendSession.incoming) {
                when (frame) {
                    is Frame.Text -> serverSession.send(Frame.Text(frame.readText()))

                    is Frame.Binary -> serverSession.send(Frame.Binary(true, frame.readBytes()))

                    is Frame.Close -> {
                        serverSession.send(Frame.Close(frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "Closed")))
                        return@launch
                    }

                    else -> Unit
                }
            }
        }

        client.join()
        server.join()
    }
}

private fun configureSdk(): ZetaSdkClient {
    testFachDienstUrl = System.getenv("FACHDIENST_URL")
        ?: error("Missing required env variable: FACHDIENST_URL")

    val smbKeystoreFile = System.getenv("SMB_KEYSTORE_FILE") ?: ""
    val smbKeystoreAlias = System.getenv("SMB_KEYSTORE_ALIAS") ?: ""
    val smbKeystorePassword = System.getenv("SMB_KEYSTORE_PASSWORD") ?: ""

    val smcbBaseUrl = System.getenv("SMCB_BASE_URL") ?: ""
    val smcbCardHandle = System.getenv("SMCB_CARD_HANDLE") ?: ""
    val smcbClientSystemId = System.getenv("SMCB_CLIENT_SYSTEM_ID") ?: ""
    val smcbMandantId = System.getenv("SMCB_MANDANT_ID") ?: ""
    val smcbUserId = System.getenv("SMCB_USER_ID") ?: ""
    val smcbWorkspaceId = System.getenv("SMCB_WORKSPACE_ID") ?: ""

    val aslTracingHeader = System.getenv("ASL_TRACING_HEADER")?.toBoolean() ?: true

    val sdk = ZetaSdk.build(
        resource = testFachDienstUrl,
        BuildConfig(
            "test_proxy",
            "0.2.0",
            "sdk-client",
            StorageConfig(store),
            object : TpmConfig {},
            AuthConfig(
                listOf(
                    "zero:audience",
                ),
                30,
                enableAslTracingHeader = aslTracingHeader,
                when {
                    smbKeystoreFile.isNotEmpty() ->
                        SmbTokenProvider(
                            SmbTokenProvider.Credentials(
                                smbKeystoreFile,
                                smbKeystoreAlias,
                                smbKeystorePassword,
                            ),
                        )

                    smcbBaseUrl.isNotEmpty() ->
                        SmcbTokenProvider(
                            SmcbTokenProvider.ConnectorConfig(
                                smcbBaseUrl,
                                smcbMandantId,
                                smcbClientSystemId,
                                smcbWorkspaceId,
                                smcbUserId,
                                smcbCardHandle,
                            ),
                        )

                    else ->
                        error("No SM-B or SMC-B configuration was provided")
                },
            ),
            clientSelfAssessment = ClientSelfAssessment("name", "clientId", "manufacturerId", "manufacturerName", "test@manufacturertestmail.de", registrationTimestamp = 0, PlatformProductId.AppleProductId("apple", "macos", listOf("bundleX"))),
            ZetaHttpClientBuilder("").disableServerValidation("true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase())),
        ),
    )

    return sdk
}

private val notForwardedHeaders = setOf(HttpHeaders.ContentType, HttpHeaders.ContentLength, HttpHeaders.TransferEncoding, HttpHeaders.Connection)
private fun shouldForwardHeader(name: String): Boolean {
    return notForwardedHeaders.none {
        it.equals(name, ignoreCase = true)
    }
}

private fun wsCustomHeaders(): Map<String, String> {
    val headers = mutableMapOf<String, String>()

    if (!headers.containsKey(POPP_TOKEN_HEADER_NAME)) {
        POPP_TOKEN?.let { headers[POPP_TOKEN_HEADER_NAME] = it }
    }

    return headers
}
