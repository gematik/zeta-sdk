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
import de.gematik.zeta.driver.model.KvnrEmailRequest
import de.gematik.zeta.driver.oidc.TestDriverOtpCallback
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdk.forget
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public val customCaPems: MutableList<String> = mutableListOf()

private const val NOTIFICATIONS_PUSHERS_PATH = "/testdriver-api/notifications/pushers"

public fun Application.testDriverRouting(
    manager: TestDriverManager = TestDriverManager(),
) {
    routing {
        route("/proxy/{path...}") {
            handle {
                forward(call, manager.httpClient, manager.config)
            }
        }

        webSocket("/proxy/{path...}") {
            val targetUrl = buildWsTargetUrl(call, manager.config)
            forwardWs(this, manager.sdk, targetUrl, manager.config)
        }

        get("/testdriver-api/authenticate") {
            authenticate(call, manager.sdk)
        }

        get("/testdriver-api/discover") {
            discover(call, manager.sdk)
        }

        get("/testdriver-api/register") {
            register(call, manager.sdk)
        }

        get("/testdriver-api/storage") {
            storage(call, manager)
        }
        get("/testdriver-api/reset") {
            resetDriver(call, manager)
        }

        post("/testdriver-api/configure") {
            configure(call, manager)
        }

        post("/oidc/kvnr-email") {
            setKvnrEmail(call, manager)
        }

        // Notification Service (push pusher/channel management)
        get(NOTIFICATIONS_PUSHERS_PATH) { getPushers(call, manager.notifications()) }
        post(NOTIFICATIONS_PUSHERS_PATH) { registerPusher(call, manager.notifications()) }
        put(NOTIFICATIONS_PUSHERS_PATH) { updatePusher(call, manager.notifications()) }
        delete(NOTIFICATIONS_PUSHERS_PATH) { deletePusher(call, manager.notifications()) }
        get("/testdriver-api/notifications/channels") { getAvailableChannels(call, manager.notifications()) }
        get("/testdriver-api/notifications/channels/local") { getLocalChannels(call, manager.notifications()) }
        post("/testdriver-api/notifications/channels/local") { setLocalChannels(call, manager.notifications()) }

        // OIDC
        post("/oidc/collect-email") { collectEmail(call, manager.otpCallback) }
        post("/oidc/resend-otp") { resendOtp(call, manager.otpCallback) }
        post("/oidc/verify-otp") { verifyOtp(call, manager.otpCallback) }
        post("/oidc/kvnr-email") { setKvnrEmail(call, manager) }
        get("/oidc/status") { otpStatus(call, manager.otpCallback) }
        get("/oidc/change-email") { changeEmail(call, manager) }

        get("/health") {
            call.respondText("alive")
        }
    }
}

private suspend fun resetDriver(call: ApplicationCall, manager: TestDriverManager) {
    try {
        manager.reset()
        reset(call, manager.sdk)
    } catch (e: Exception) {
        Log.e(e) { "Failed to reset TestDriver" }
        call.respond(HttpStatusCode.InternalServerError, "Failed to reset TestDriver: ${e.message}")
    }
}

private suspend fun storage(call: ApplicationCall, manager: TestDriverManager) {
    try {
        val entries = manager.getStorageSnapshot()
        call.respondText(
            Json.encodeToString(NestedUnquotedJson, entries),
            ContentType.Application.Json,
        )
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

private suspend fun configure(call: RoutingCall, manager: TestDriverManager) {
    try {
        val request = call.receive<ConfigureRequest>()
        manager.configure(request)
        call.respondText("Test driver configured successfully", ContentType.Text.Plain)
    } catch (e: Exception) {
        Log.e(e) { "Failed to configure TestDriver" }
        call.respond(HttpStatusCode.BadRequest, "Failed to configure TestDriver: ${e.message}")
    }
}

private suspend fun setKvnrEmail(call: RoutingCall, manager: TestDriverManager) {
    try {
        val request = call.receive<KvnrEmailRequest>()
        manager.setKvnrEmail(request.kvnr, request.email)
        manager.sdk.forget().getOrThrow()
        call.respondText("KVNR and email set successfully", ContentType.Text.Plain)
    } catch (e: Exception) {
        Log.e(e) { "Failed to set KVNR and email" }
        call.respond(HttpStatusCode.BadRequest, "Failed to set KVNR and email: ${e.message}")
    }
}

private suspend fun collectEmail(call: ApplicationCall, otpCallback: TestDriverOtpCallback) {
    val body = call.receiveText()
    val email = runCatching { Json.decodeFromString<EmailRequest>(body).email }
        .getOrElse { body.trim() }

    val delivered = otpCallback.provideEmail(email)
    if (delivered) {
        call.respond(HttpStatusCode.Accepted, "email accepted")
    } else {
        call.respond(HttpStatusCode.Conflict, "no pending awaitEmail() call")
    }
}

private suspend fun verifyOtp(call: ApplicationCall, otpCallback: TestDriverOtpCallback) {
    val body = call.receiveText()
    val code = runCatching { Json.decodeFromString<OtpRequest>(body).code }
        .getOrElse { body.trim() }

    val delivered = otpCallback.provideOtp(code)
    if (delivered) {
        call.respond(HttpStatusCode.Accepted, "otp sent")
    } else {
        call.respond(HttpStatusCode.Conflict, "no pending awaitOtp() call")
    }
}

private suspend fun resendOtp(call: ApplicationCall, otpCallback: TestDriverOtpCallback) {
    val delivered = otpCallback.requestResend()
    if (delivered) {
        call.respond(HttpStatusCode.Accepted, "resend requested")
    } else {
        call.respond(HttpStatusCode.Conflict, "no pending awaitOtp() call")
    }
}

private suspend fun otpStatus(call: ApplicationCall, otpCallback: TestDriverOtpCallback) {
    call.respond(
        HttpStatusCode.OK,
        OtpStatusResponse(
            awaiting = otpCallback.currentlyAwaiting(),
            lastEmailHint = otpCallback.lastEmailHint,
            lastRejected = otpCallback.lastRejected,
        ),
    )
}

private suspend fun changeEmail(call: ApplicationCall, manager: TestDriverManager) {
    val body = call.receiveText()
    val newEmail = runCatching { Json.decodeFromString<EmailRequest>(body).email }
        .getOrElse {
            Log.d { "Failed to parse request body: $body" }
            call.respond(HttpStatusCode.BadRequest, "Invalid request body, expected JSON: email}")
            return
        }

    Log.d { "Requesting email change to: $newEmail" }

    val result = manager.sdk.changeEmail(newEmail)
    if (result.isSuccess) {
        Log.d { "Email change accepted for: $newEmail" }
        call.respond(HttpStatusCode.Accepted, "Email changed")
    } else {
        val error = result.exceptionOrNull()
        Log.d { "Failed for $newEmail: ${error?.message}" }
        call.respond(HttpStatusCode.Conflict, "Failed to change email: ${error?.message}")
    }
}

@Serializable
private data class EmailRequest(val email: String)

@Serializable
private data class OtpRequest(val code: String)

@Serializable
private data class OtpStatusResponse(
    val awaiting: String,
    val lastEmailHint: String?,
    val lastRejected: Boolean,
)
