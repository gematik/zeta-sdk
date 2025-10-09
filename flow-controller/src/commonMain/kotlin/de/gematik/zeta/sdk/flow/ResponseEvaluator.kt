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
import io.ktor.client.request.HttpRequestBuilder

/**
 * Maps a (request, response) pair to the next flow decision.
 * Keep it stateless and deterministic for testability.
 */
fun interface ResponseEvaluator {
    fun evaluate(req: HttpRequestBuilder, resp: HttpClientCall): FlowDirective
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
    override fun evaluate(req: HttpRequestBuilder, resp: HttpClientCall): FlowDirective =
        when (resp.response.status.value) {
            in 200..299 -> FlowDirective.Proceed(resp.response)
            // 40x -> FlowDirective.Perform(FlowNeed.Authentication)
            // 40x -> FlowDirective.Perform(FlowNeed.Attestation)
            else -> {
                Log.e { "Requests failed. URL: ${resp.request.url} - Status: ${resp.response.status}" }
                FlowDirective.Abort(Exception("Unhandled status ${resp.response.status}"))
            }
        }
}
