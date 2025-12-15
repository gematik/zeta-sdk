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

/**
 * Outcome of executing a [CapabilityHandler].
 */
sealed interface CapabilityResult {
    /**
     * The need was satisfied; no immediate retry is required.
     * The orchestrator may still apply an evaluator-provided mutation.
     */
    object Done : CapabilityResult

    /**
     * Ask the orchestrator to retry the same request, after applying [mutate].
     * Use this to add headers or tweak the request before the next send.
     */
    data class RetryRequest(val mutate: suspend (HttpRequestBuilder) -> Unit = {}) : CapabilityResult
}
