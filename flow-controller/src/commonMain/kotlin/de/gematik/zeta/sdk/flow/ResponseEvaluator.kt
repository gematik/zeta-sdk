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
import de.gematik.zeta.sdk.network.http.client.isAslResponse
import io.ktor.client.call.HttpClientCall
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/**
 * Maps a (request, response) pair to the next flow decision.
 * Keep it stateless and deterministic for testability.
 */
fun interface ResponseEvaluator {
    suspend fun evaluate(call: HttpClientCall, ctx: FlowContext, retryState: FlowOrchestrator.RetryState): FlowDirective

    suspend fun handle(
        call: HttpClientCall,
        ctx: FlowContext,
        retryState: FlowOrchestrator.RetryState,
        statusCode: HttpStatusCode,
        errorOrigin: String?,
    ): FlowDirective {
        return when (statusCode.value) {
            401, 403 -> handleUnauthorized(
                call = call,
                ctx = ctx,
                retryState = retryState,
                statusCode = statusCode,
                errorOrigin = errorOrigin,
            )

            404 -> handleDiscovery(
                call = call,
                ctx = ctx,
                retryState = retryState,
                errorOrigin = errorOrigin,
            )
            else -> FlowDirective.Proceed(call.response)
        }
    }

    suspend fun handleUnauthorized(
        call: HttpClientCall,
        ctx: FlowContext,
        retryState: FlowOrchestrator.RetryState,
        statusCode: HttpStatusCode,
        errorOrigin: String?,
    ): FlowDirective {
        if (!isPep(errorOrigin)) {
            Log.d { "${statusCode.value} not originating from PEP: returning response to caller" }
            return FlowDirective.Proceed(call.response)
        }

        return when (retryState.stepUpAttempts) {
            0 -> {
                Log.w { "${statusCode.value} from PEP: trying refresh token before full authentication" }
                retryState.stepUpAttempts++
                ctx.authenticationStorage.clearAccessToken()
                FlowDirective.Perform(FlowNeed.Authentication)
            }

            1 -> {
                Log.w { "${statusCode.value} from PEP after refresh: performing full authentication" }
                retryState.stepUpAttempts++
                ctx.authenticationStorage.clear()
                FlowDirective.Perform(FlowNeed.Authentication)
            }

            else -> FlowDirective.Abort(call.response, ZetaClientError.StepUpFailed())
        }
    }

    private suspend fun handleDiscovery(
        call: HttpClientCall,
        ctx: FlowContext,
        retryState: FlowOrchestrator.RetryState,
        errorOrigin: String?,
    ): FlowDirective {
        if (!isPep(errorOrigin)) {
            return FlowDirective.Proceed(call.response)
        }

        if (retryState.hasAttemptedDiscoveryRefresh) {
            Log.e { "404 from PEP persists after discovery refresh" }
            return FlowDirective.Proceed(call.response)
        }

        retryState.hasAttemptedDiscoveryRefresh = true

        Log.w { "404 from PEP. Invalidating service discovery cache and repeating discovery." }
        ctx.configurationStorage.invalidateDiscovery()

        return FlowDirective.Perform(FlowNeed.ConfigurationFiles)
    }

    private fun isPep(errorOrigin: String?): Boolean =
        errorOrigin.equals(PEP, ignoreCase = true)

    companion object {
        const val ZETA_ERROR_ORIGIN = "zeta-error-origin"
        const val PEP = "pep"
    }
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
    override suspend fun evaluate(call: HttpClientCall, ctx: FlowContext, retryState: FlowOrchestrator.RetryState): FlowDirective {
        val path = call.response.request.url.encodedPath
        val ct = call.response.headers[HttpHeaders.ContentType]

        return if (isAslResponse(path, ct)) {
            AslResponseEvaluator().evaluate(call, ctx, retryState)
        } else {
            StatusCodeEvaluator().evaluate(call, ctx, retryState)
        }
    }
}
