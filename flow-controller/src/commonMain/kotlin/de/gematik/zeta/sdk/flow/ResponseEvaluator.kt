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
import de.gematik.zeta.sdk.asl.decodeAslError
import de.gematik.zeta.sdk.network.http.client.isAslEncryptedResponse
import io.ktor.client.call.HttpClientCall
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/**
 * Maps a (request, response) pair to the next flow decision.
 * Keep it stateless and deterministic for testability.
 */
fun interface ResponseEvaluator {
    suspend fun evaluate(call: HttpClientCall, ctx: FlowContext): FlowDirective
}

/**
 * Default response evaluator.
 *
 * - 2xx → [FlowDirective.Proceed]
 * - everything else → [FlowDirective.Abort]
 *
 * Extend by uncommenting/adding rules (e.g., 40x->Authentication, 40x->Attestation).
 */
class ResponseEvaluatorImpl : ResponseEvaluator {
    private var attempt = 0
    private val maxAslRetryAttempts = 1
    override suspend fun evaluate(call: HttpClientCall, ctx: FlowContext): FlowDirective {
        val status = call.response.status
        val path = call.response.request.url.encodedPath
        val ct = call.response.headers[HttpHeaders.ContentType]

        if (isAslEncryptedResponse(path, ct)) {
            return when (status) {
                HttpStatusCode.OK -> {
                    attempt = 0
                    FlowDirective.Proceed(call.response)
                }

                else -> {
                    val errorMessage = decodeAslError(call.response)
                    Log.e { "Establishing ASL error [${errorMessage.errorCode}] ${errorMessage.errorMessage}" }
                    if (attempt < maxAslRetryAttempts) {
                        attempt++
                        Log.i { "Retry establishing ASL attempt $attempt" }
                        ctx.aslStorage.clear(ctx.resource)
                        FlowDirective.Perform(FlowNeed.Asl)
                    } else {
                        attempt = 0
                        FlowDirective.Abort(call.response, Exception("Establishing ASL failed after $maxAslRetryAttempts attempts}"))
                    }
                }
            }
        } else {
            return when (status.value) {
                in 200..299 -> FlowDirective.Proceed(call.response)

                else -> {
                    Log.e { "Requests failed. URL: ${call.request.url} - Status: $status" }
                    FlowDirective.Abort(call.response, Exception("Unhandled status $status"))
                }
            }
        }
    }
}
