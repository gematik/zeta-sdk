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
import io.ktor.client.call.HttpClientCall

class StatusCodeEvaluator : ResponseEvaluator {
    companion object {
        const val DEFAULT_RETRY_DELAY_MS = 5000L
    }
    override suspend fun evaluate(call: HttpClientCall, ctx: FlowContext, retryState: FlowOrchestrator.RetryState): FlowDirective {
        val status = call.response.status
        val errorOrigin = call.response.headers[ResponseEvaluator.ZETA_ERROR_ORIGIN]
        return when (status.value) {
            in 200..299 -> FlowDirective.Proceed(call.response)

            401, 403, 404 -> handle(
                call = call,
                ctx = ctx,
                retryState = retryState,
                statusCode = status,
                errorOrigin = errorOrigin,
            )

            400, 405, 409 -> FlowDirective.Proceed(call.response)

            429 -> {
                val retryAfterMs = call.response.headers["retry-after"]
                    ?.toLongOrNull()
                    ?.times(1000)
                    ?: DEFAULT_RETRY_DELAY_MS
                Log.w { "Rate limited: retrying after ${retryAfterMs}ms" }
                FlowDirective.Perform(FlowNeed.Retry(retryAfterMs))
            }

            500 -> {
                Log.w { "Server error 500: retrying after ${DEFAULT_RETRY_DELAY_MS}ms" }
                ctx.aslStorage.clear()
                FlowDirective.Perform(FlowNeed.Retry(DEFAULT_RETRY_DELAY_MS))
            }

            502, 504 -> {
                Log.w { "Proxy error ${call.response.status.value}: retrying after ${DEFAULT_RETRY_DELAY_MS}ms" }
                FlowDirective.Perform(FlowNeed.Retry(DEFAULT_RETRY_DELAY_MS))
            }
            in 500..599 -> FlowDirective.Abort(call.response, ZetaClientError.ServerError(status.value))
            else -> {
                Log.e { "Unhandled status $status for ${call.request.url}" }
                FlowDirective.Abort(call.response, ZetaClientError.Unknown(status.value))
            }
        }
    }
}
