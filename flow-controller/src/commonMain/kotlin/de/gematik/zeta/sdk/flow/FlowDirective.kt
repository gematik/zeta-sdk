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

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse

/**
 * Decision returned by a [ResponseEvaluator] after inspecting a response.
 */
sealed interface FlowDirective {
    /** The flow can finish; return this [response] to the caller. */
    data class Proceed(val response: HttpResponse) : FlowDirective

    /**
     * Perform a [need] before retrying. The optional [mutate] can adjust the
     * current [HttpRequestBuilder] (e.g., add headers) prior to the next send.
     */
    data class Perform(val need: FlowNeed, val mutate: (HttpRequestBuilder) -> Unit = {}) : FlowDirective

    /** Irrecoverable error: stop the flow and throw [error]. */
    data class Abort(val response: HttpResponse, val error: Throwable) : FlowDirective
}
