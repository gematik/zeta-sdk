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

/**
 * Executes side effects required to satisfy a [FlowNeed].
 *
 * Typical responsibilities:
 * - call an API (e.g., fetch configuration, tokens),
 * - update [FlowContext.storage],
 * - mutate the pending request so the next send succeeds.
 *
 */
interface CapabilityHandler {
    /** True if this handler knows how to satisfy the given [need]. */
    fun canHandle(need: FlowNeed): Boolean

    /**
     * Performs the side effect for [need]. Return:
     * - [CapabilityResult.Done] to continue the flow (optionally applying the evaluator’s mutation),
     * - [CapabilityResult.RetryRequest] to mutate the request and trigger an immediate retry.
     */
    suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult
}
