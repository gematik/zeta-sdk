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

package de.gematik.zeta.sdk.flow.handler

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.asl.AslApi
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed

/**
 * Handles the ASL capability for a given protected resource.
 *
 * This handler checks whether the target resource requires ASL encryption and, if so,
 * instructs the caller to **retry the request** after applying encryption.
 *
 * @constructor Creates an ASL capability handler bound to a specific resource.
 *
 * @see CapabilityHandler
 * @see CapabilityResult
 */
class AslHandler(
    private val asl: AslApi,
) : CapabilityHandler {
    /** Returns `true` only for [FlowNeed.Asl]. */
    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.Asl

    /**
     * Evaluates the ASL requirement for the configured [resource].
     *
     * - When ASL is **REQUIRED**, returns [CapabilityResult.RetryRequest] with a `mutate` callback
     *   that encrypts the request via [AslApi.encrypt], allowing the caller to resend.
     * - Otherwise, returns [CapabilityResult.Done].
     *
     * @param need Expected to be [FlowNeed.Asl].
     * @param ctx Flow context providing configuration and network facilities.
     * @return [CapabilityResult.RetryRequest] if encryption must be applied; [CapabilityResult.Done] otherwise.
     * @throws IllegalStateException if the protected resource cannot be resolved.
     */
    override suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult {
        return if (ctx.configurationStorage.aslRequired(ctx.resource)) {
            Log.d { "Starting ASL encryption for resource $ctx.resource" }
            CapabilityResult.RetryRequest { req ->
                asl.encrypt(req)
            }
        } else {
            Log.d { "Resource $ctx.resource does not require ASL" }
            CapabilityResult.Done
        }
    }
}
