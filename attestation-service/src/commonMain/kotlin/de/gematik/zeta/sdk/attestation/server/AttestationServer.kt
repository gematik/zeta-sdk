
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

package de.gematik.zeta.sdk.attestation.server

import AttestationService
import ServiceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.model.AttestationRequest
import de.gematik.zeta.sdk.attestation.model.AttestationResponse
import de.gematik.zeta.sdk.attestation.model.FileMonitorRequest
import de.gematik.zeta.sdk.attestation.model.FileMonitorResponse
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityRequest
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class AttestationServer(
    private val config: ServiceConfig,
    private val service: AttestationService,
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = true
    }

    fun start() {
        embeddedServer(CIO, port = config.port) {
            install(ContentNegotiation) {
                json(json)
            }
            install(WebSockets)
            configureRouting()
        }.start(wait = true)
    }

    private fun Application.configureRouting() {
        routing {
            post("/attest") {
                try {
                    val request = call.receive<AttestationRequest>()
                    val response = service.buildAttestationResponse(request, call.request.origin)

                    call.respondText(
                        text = json.encodeToString(AttestationResponse.serializer(), response),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
                } catch (e: Exception) {
                    Log.e { e.stackTraceToString() }
                    call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal error")
                }
            }

            post("/verify-integrity") {
                try {
                    val request = call.receive<VerifyIntegrityRequest>()
                    val verifyIntegrity = service.verifyIntegrity(request)
                    call.respond(HttpStatusCode.OK, verifyIntegrity)
                } catch (ex: Exception) {
                    Log.e { ex.stackTraceToString() }
                    call.respond(HttpStatusCode.InternalServerError, ex.message ?: "Error")
                }
            }

            get("/health") {
                try {
                    val health = service.health()
                    call.respond(HttpStatusCode.OK, health)
                } catch (ex: Exception) {
                    Log.e { ex.stackTraceToString() }
                    call.respond(HttpStatusCode.InternalServerError, ex.message ?: "Error")
                }
            }

            get("/process-pid") {
                try {
                    val pid = service.processPid(call.request.origin)
                    call.respond(HttpStatusCode.OK, pid)
                } catch (ex: Exception) {
                    Log.e { ex.stackTraceToString() }
                    call.respond(HttpStatusCode.InternalServerError, ex.message ?: "Error")
                }
            }

            webSocket("/file-monitor") {
                try {
                    for (frame in incoming) {
                        Log.i { "Incoming Frame: $frame" }
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            Log.i { "Frame.Text: $text" }
                            val request = Json.decodeFromString<FileMonitorRequest>(text)
                            service.fileMonitor(request) { file, event ->
                                val response = Json.encodeToString(FileMonitorResponse(file, event))
                                runBlocking {
                                    send(Frame.Text(response))
                                }
                            }
                        }
                    }
                    service.stopFileMonitor()
                } catch (ex: Exception) {
                    Log.e { ex.stackTraceToString() }
                    call.respond(HttpStatusCode.InternalServerError, ex.message ?: "Error")
                }
            }

            webSocket("/integrity") {
                try {
                    val initialState = service.currentIntegrityState()
                    val initialResponse = Json.encodeToString(VerifyIntegrityResponse.serializer(), initialState)
                    send(Frame.Text(initialResponse))

                    val unsubscribe = service.subscribeIntegrity { state ->
                        val response = Json.encodeToString(VerifyIntegrityResponse.serializer(), state)
                        runBlocking {
                            send(Frame.Text(response))
                        }
                    }

                    for (frame in incoming) {
                        if (frame is Frame.Close) {
                            break
                        }
                    }

                    unsubscribe()
                } catch (ex: Exception) {
                    Log.e { ex.stackTraceToString() }
                    call.respond(HttpStatusCode.InternalServerError, ex.message ?: "Error")
                }
            }
        }
    }
}
