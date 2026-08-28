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
import io.ktor.http.HttpStatusCode

class AslResponseEvaluator : ResponseEvaluator {
    override suspend fun evaluate(
        call: HttpClientCall,
        ctx: FlowContext,
        retryState: FlowOrchestrator.RetryState,
    ): FlowDirective {
        return when (call.response.status.value) {
            200 -> {
                val attributes = call.response.call.attributes
                val innerStatus = attributes.getOrNull(InnerStatusKey)
                val innerHeaders = attributes.getOrNull(InnerHeadersKey)
                val errorOrigin = innerHeaders
                    ?.entries
                    ?.firstOrNull {
                        it.key.equals(
                            ZETA_ERROR_ORIGIN,
                            ignoreCase = true,
                        )
                    }
                    ?.value

                Log.d { "ASL: 200 transport response. " + "InnerStatus=${innerStatus ?: "none"}" }

                when (innerStatus) {
                    401 -> handle(
                        call = call,
                        ctx = ctx,
                        retryState = retryState,
                        statusCode = HttpStatusCode.Unauthorized,
                        errorOrigin = errorOrigin,
                    )

                    403 -> handle(
                        call = call,
                        ctx = ctx,
                        retryState = retryState,
                        statusCode = HttpStatusCode.Forbidden,
                        errorOrigin = errorOrigin,
                    )

                    404 -> handle(
                        call = call,
                        ctx = ctx,
                        retryState = retryState,
                        statusCode = HttpStatusCode.NotFound,
                        errorOrigin = errorOrigin,
                    )

                    else -> FlowDirective.Proceed(call.response)
                }
            }
            500 -> {
                Log.w { "ASL: 500 response. Retrying handshake" }
                ctx.aslStorage.clear()
                FlowDirective.Perform(FlowNeed.Asl)
            }

            else -> {
                val error = runCatching { decodeAslError(call.response) }
                    .getOrElse { cause ->
                        Log.e(cause) { "Failed to decode ASL error body (status=${call.response.status.value})" }
                        AslErrorMessage(
                            messageType = "Error",
                            errorCode = -1,
                            errorMessage = "Unparseable ASL error response (HTTP ${call.response.status.value})",
                        )
                    }
                Log.e { "Establishing ASL error [${error.errorCode}] ${error.errorMessage}" }
                FlowDirective.Abort(call.response, ZetaClientError.AslError(error.errorCode, error.errorMessage))
            }
        }
    }
}
