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

import de.gematik.zeta.driver.model.ConfigureRequest
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.lang.System
import kotlin.sequences.forEach

private val store = InMemoryStorage()
private lateinit var globalSdk: ZetaSdkClient
private lateinit var globalConfig: SdkInstanceConfig
public val customCaPems: MutableList<String> = mutableListOf()

private var globalHttpClient: ZetaHttpClient? = null
public fun Application.testDriverRouting() {
    globalConfig = SdkInstanceConfig.fromFileOrEnv()
    rebuildClient()

    routing {
        route("/proxy/{path...}") {
            handle {
                forward(call, globalHttpClient!!, globalConfig)
            }
        }

        webSocket("/proxy/{path...}") {
            val targetUrl = buildWsTargetUrl(call, globalConfig)
            forwardWs(this, globalSdk, targetUrl, globalConfig)
        }

        get("/testdriver-api/authenticate") { authenticate(call, globalSdk) }
        get("/testdriver-api/discover") { discover(call, globalSdk) }
        get("/testdriver-api/register") { register(call, globalSdk) }
        get("/testdriver-api/storage") { storage(call) }
        get("/testdriver-api/reset") { resetDriver(call) }
        post("/testdriver-api/configure") { configure(call) }
        get("/health") { call.respondText("alive") }
    }
}

private fun rebuildClient() {
    globalSdk = newSdk(storage = store, globalConfig)
    globalHttpClient = globalSdk.httpClient {
        logging(LogLevel.ALL, logger)
        disableServerValidation(
            "true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()),
        )
        dispatcher(300, 300)
        customCaPems.forEach { pem -> addCaPem(pem) }
    }
}

private suspend fun resetDriver(call: ApplicationCall) {
    try {
        globalConfig = SdkInstanceConfig.fromFileOrEnv()
        customCaPems.clear()
        rebuildClient()
        return reset(call, globalSdk)
    } catch (e: Exception) {
        Log.e(e) { "Failed to reset TestDriver" }
        call.respond(HttpStatusCode.InternalServerError, "Failed to reset TestDriver: ${e.message}")
    }
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

private suspend fun configure(call: RoutingCall) {
    try {
        val request = call.receive<ConfigureRequest>()
        if (request.resource.isNotEmpty()) {
            globalConfig = globalConfig.copy(fachdienstUrl = request.resource)
        }
        if (request.caCertificatePem.isNotEmpty()) {
            customCaPems.clear()
            customCaPems.add(request.caCertificatePem)
        }
        rebuildClient()
        call.respondText("Test driver configured successfully", ContentType.Text.Plain)
    } catch (e: Exception) {
        Log.e(e) { "Failed to configure TestDriver" }
        call.respond(HttpStatusCode.BadRequest, "Failed to configure TestDriver: ${e.message}")
    }
}

private fun ZetaSdkClient.buildHttpClient(
    configureCa: (ZetaHttpClientBuilder.() -> Unit)? = null,
): ZetaHttpClient {
    return this.httpClient {
        logging(LogLevel.ALL, logger)
        disableServerValidation(
            "true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()),
        )
        dispatcher(300, 300)

        customCaPems.forEach { pem ->
            addCaPem(pem)
        }

        configureCa?.invoke(this)
    }
}
