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

package de.gematik.zeta.sdk.flow

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.asl.AslErrorMessage
import de.gematik.zeta.sdk.asl.decodeAslError
import de.gematik.zeta.sdk.flow.ResponseEvaluator.Companion.ZETA_ERROR_ORIGIN
import de.gematik.zeta.sdk.network.http.client.InnerHeadersKey
import de.gematik.zeta.sdk.network.http.client.InnerStatusKey
import io.ktor.client.call.HttpClientCall
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AslResponseEvaluator : ResponseEvaluator {
    override suspend fun evaluate(
        call: HttpClientCall,
        ctx: FlowContext,
        retryState: FlowOrchestrator.RetryState,
    ): FlowDirective {
        val status = call.response.status.value
        if (status == 200) return evaluateInner(call, ctx, retryState)

        if (isPepAslSessionExpired(call.response)) {
            Log.w { "ASL: PEP reports AslSessionExpired. Re-establishing channel" }
            return reestablishAsl(call, ctx, retryState, "ASL session expired or unknown", -1)
        }

        val decoded = runCatching { decodeAslError(call.response) }
        val aslError = decoded.getOrNull()
        return when (status) {
            403 if aslError != null && aslError.errorCode in RECOVERABLE_ASL_ERRORS -> {
                Log.w { "ASL: 403 [${aslError.errorCode}] ${aslError.errorMessage}. Re-establishing channel" }
                reestablishAsl(call, ctx, retryState, "ASL session invalid [${aslError.errorCode}] ${aslError.errorMessage}", aslError.errorCode)
            }
            500 -> {
                Log.w { "ASL: 500 response [${aslError?.errorCode}] ${aslError?.errorMessage}. Re-establishing channel" }
                reestablishAsl(call, ctx, retryState, aslError?.errorMessage ?: "HTTP 500 from server")
            }
            else -> {
                val error = aslError ?: run {
                    Log.e(decoded.exceptionOrNull()) { "Failed to decode ASL error body (status=$status)" }
                    AslErrorMessage(messageType = "Error", errorCode = -1, errorMessage = "Unparseable ASL error response (HTTP $status)")
                }
                Log.e { "Establishing ASL error [${error.errorCode}] ${error.errorMessage}" }
                FlowDirective.Abort(call.response, ZetaClientError.AslError(error.errorCode, error.errorMessage))
            }
        }
    }

    private suspend fun evaluateInner(
        call: HttpClientCall,
        ctx: FlowContext,
        retryState: FlowOrchestrator.RetryState,
    ): FlowDirective {
        val attributes = call.response.call.attributes
        val innerStatus = attributes.getOrNull(InnerStatusKey)
        val innerHeaders = attributes.getOrNull(InnerHeadersKey)
        val errorOrigin = innerHeaders
            ?.entries
            ?.firstOrNull { it.key.equals(ZETA_ERROR_ORIGIN, ignoreCase = true) }
            ?.value

        Log.d { "ASL: 200 transport response. InnerStatus=${innerStatus ?: "none"}" }

        return when (innerStatus) {
            401 -> handle(call, ctx, retryState, HttpStatusCode.Unauthorized, errorOrigin)
            403 -> handle(call, ctx, retryState, HttpStatusCode.Forbidden, errorOrigin)
            404 -> handle(call, ctx, retryState, HttpStatusCode.NotFound, errorOrigin)
            else -> FlowDirective.Proceed(call.response)
        }
    }

    private suspend fun isPepAslSessionExpired(response: HttpResponse): Boolean {
        if (response.status.value != 403) return false
        val origin = response.headers[ZETA_ERROR_ORIGIN]
        if (!origin.equals(PEP_ERROR_ORIGIN, ignoreCase = true)) return false

        val error = runCatching {
            Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

        return error == ASL_SESSION_EXPIRED
    }

    private suspend fun reestablishAsl(
        call: HttpClientCall,
        ctx: FlowContext,
        retryState: FlowOrchestrator.RetryState,
        reason: String,
        errorCode: Int = -1,
    ): FlowDirective {
        ctx.aslStorage.clear()
        if (retryState.hasAttemptedAslReestablish) {
            Log.e { "ASL: $reason, but channel was already re-established once. Giving up" }
            return FlowDirective.Abort(call.response, ZetaClientError.AslError(errorCode, reason))
        }
        retryState.hasAttemptedAslReestablish = true
        return FlowDirective.Perform(FlowNeed.Asl)
    }

    private companion object {
        const val PEP_ERROR_ORIGIN = "pep"
        const val ASL_SESSION_EXPIRED = "AslSessionExpired"
        val RECOVERABLE_ASL_ERRORS = setOf(3, 8, 9)
    }
}
