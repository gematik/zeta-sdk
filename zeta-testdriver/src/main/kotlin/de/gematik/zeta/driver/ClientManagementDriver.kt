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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailRequest
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.identity.ClientManagementException
import de.gematik.zeta.sdk.authentication.identity.EmailChallenge
import de.gematik.zeta.sdk.authentication.identity.PepError
import de.gematik.zeta.sdk.authentication.identity.ProblemDetails
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
public data class VerifyEmailChangeRequest(val code: String)

public suspend fun changeEmail(call: ApplicationCall, sdk: ZetaSdkClient) {
    try {
        val newEmail = call.receive<ChangeEmailRequest>().newEmail
        Log.i { "[SDK-DRIVER] changeEmail called: new_email=$newEmail" }
        val response: ChangeEmailResponse = sdk.changeEmail(newEmail).getOrThrow()
        Log.i { "[SDK-DRIVER] changeEmail succeeded: $response" }
        call.respond(HttpStatusCode.Accepted, response)
    } catch (ex: Throwable) {
        respondClientManagementError(call, ex)
    }
}

public suspend fun resendEmailChangeCode(call: ApplicationCall, sdk: ZetaSdkClient) {
    try {
        Log.i { "[SDK-DRIVER] resendEmailChangeCode called" }
        val challenge: EmailChallenge = sdk.resendEmailChangeCode().getOrThrow()
        Log.i { "[SDK-DRIVER] resendEmailChangeCode succeeded: $challenge" }
        call.respond(HttpStatusCode.Accepted, challenge)
    } catch (ex: Throwable) {
        respondClientManagementError(call, ex)
    }
}

public suspend fun verifyEmailChange(call: ApplicationCall, sdk: ZetaSdkClient) {
    try {
        val code = call.receive<VerifyEmailChangeRequest>().code
        Log.i { "[SDK-DRIVER] verifyEmailChange called" }
        sdk.verifyEmailChange(code).getOrThrow()
        Log.i { "[SDK-DRIVER] verifyEmailChange succeeded" }
        val verified: ChangeEmailResponse = ChangeEmailResponse.Verified
        call.respond(HttpStatusCode.OK, verified)
    } catch (ex: Throwable) {
        respondClientManagementError(call, ex)
    }
}

public suspend fun listClients(call: ApplicationCall, sdk: ZetaSdkClient) {
    try {
        Log.i { "[SDK-DRIVER] listClients called" }
        val clients: List<RegisteredClient> = sdk.listClients().getOrThrow()
        Log.i { "[SDK-DRIVER] listClients succeeded: ${clients.size} client(s)" }
        call.respond(HttpStatusCode.OK, clients)
    } catch (ex: Throwable) {
        respondClientManagementError(call, ex)
    }
}

public suspend fun deleteClient(call: ApplicationCall, sdk: ZetaSdkClient) {
    try {
        val clientId = call.parameters["client_id"].orEmpty()
        Log.i { "[SDK-DRIVER] deleteClient called: client_id=$clientId" }
        sdk.deleteClient(clientId).getOrThrow()
        Log.i { "[SDK-DRIVER] deleteClient succeeded: client_id=$clientId" }
        call.respond(HttpStatusCode.NoContent)
    } catch (ex: Throwable) {
        respondClientManagementError(call, ex)
    }
}

private suspend fun respondClientManagementError(call: ApplicationCall, ex: Throwable) {
    val (status, body) = clientManagementError(ex)
    Log.e { "[SDK-DRIVER] client management failed: $status ${ex::class.simpleName}: ${ex.message}" }
    call.respond(status, body)
}

internal fun clientManagementError(ex: Throwable): Pair<HttpStatusCode, JsonElement> {
    if (ex !is ClientManagementException) {
        val status = if (ex is IllegalArgumentException || ex is BadRequestException) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
        return status to message(ex)
    }
    val body = ex.problem?.let { Json.encodeToJsonElement(ProblemDetails.serializer(), it) }
        ?: ex.pepError?.let { Json.encodeToJsonElement(PepError.serializer(), it) }
        ?: message(ex)
    return ex.response.status to body
}

private fun message(ex: Throwable): JsonElement = buildJsonObject { put("message", ex.message) }
