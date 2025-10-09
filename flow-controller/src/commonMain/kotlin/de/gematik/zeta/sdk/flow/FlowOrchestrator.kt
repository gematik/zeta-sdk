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
import io.ktor.client.statement.HttpResponse

/**
 * Coordinates request execution with the Flow components.
 *
 * The [FlowOrchestrator] is responsible for:
 * 1. Running a [RequestEvaluator] before the first network hop
 *    to decide if there are any pre-send needs (e.g. authentication).
 * 2. Executing those needs via the registered [CapabilityHandler]s,
 *    which can mutate the request (add headers, retry markers, etc.).
 * 3. Sending the request once (through [FlowContext.client]) and
 *    passing the response to a [ResponseEvaluator].
 * 4. Acting on the evaluator’s directive:
 *    - [FlowDirective.Proceed]: return the response.
 *    - [FlowDirective.Perform]: execute another need and retry.
 *    - [FlowDirective.Abort]: throw the reported error.
 *
 * The orchestrator loops until the response evaluator decides the
 * flow can proceed or abort. Retries reuse the same
 * [HttpRequestBuilder], so request bodies and headers are preserved
 * across attempts.
 *
 * Typical usage:
 * ```
 * val orchestrator = FlowOrchestrator(
 *     requestEvaluator = RequestEvaluatorImpl(),
 *     responseEvaluator = ResponseEvaluatorImpl(),
 *     handlers = listOf(AuthHandler(), RetryHandler())
 * )
 *
 * val response = orchestrator.run(request, flowContext)
 * ```
 *
 * @param requestEvaluator decides needs before the first send
 * @param responseEvaluator decides how to act on responses
 * @param handlers components that can handle specific [FlowNeed]s
 */
class FlowOrchestrator(
    private val handlers: List<CapabilityHandler>,
    private val requestEvaluator: RequestEvaluator = RequestEvaluatorImpl(),
    private val responseEvaluator: ResponseEvaluator = ResponseEvaluatorImpl(),
) {
    /**
     * Executes the given [original] request inside a flow-aware loop.
     *
     * @param original the original request builder
     * @param ctx provides access to the client and storage
     * @return the final [HttpResponse] once the flow succeeds
     * @throws Exception if the response evaluator decides to abort
     */
    suspend fun run(original: HttpRequestBuilder, ctx: FlowContext): HttpClientCall {
        val req = HttpRequestBuilder().takeFrom(original)

        val requestNeeds = requestEvaluator.evaluate(req, ctx.storage)
        for (need in requestNeeds) executeNeed(need, req, ctx)

        while (true) {
            val resp = ctx.client.executeOnce(req)
            when (val directive = responseEvaluator.evaluate(req, resp.call)) {
                is FlowDirective.Proceed -> return resp.call
                is FlowDirective.Perform -> executeNeed(directive.need, req, ctx, evaluatorMutation = directive.mutate)
                is FlowDirective.Abort -> {
                    Log.e { "Request failed with error: ${directive.error.message}" }
                    throw directive.error
                }
            }
        }
    }

    /**
     * Executes a single [FlowNeed] using the first matching handler.
     * The handler may mutate the [req] or schedule a retry.
     */
    private suspend fun executeNeed(
        need: FlowNeed,
        req: HttpRequestBuilder,
        ctx: FlowContext,
        evaluatorMutation: ((HttpRequestBuilder) -> Unit)? = null,
    ) {
        Log.i { "Before proceeding with the request, the flow needs must executed: $need" }
        val handler = handlers.firstOrNull { it.canHandle(need) }
            ?: error("No handler for need: $need")

        when (val result = handler.handle(need, ctx)) {
            CapabilityResult.Done -> {
                Log.i { "Flow executed successfully, proceeding with request" }
                evaluatorMutation?.invoke(req)
            }
            is CapabilityResult.RetryRequest -> {
                Log.i { "Retrying the request" }
                result.mutate(req)
            }
        }
    }
}
