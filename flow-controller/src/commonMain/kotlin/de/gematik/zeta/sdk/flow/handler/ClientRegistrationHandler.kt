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

import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApi
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorage
import de.gematik.zeta.sdk.clientregistration.PostureProvider
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.clientregistration.model.Platform.SOFTWARE
import de.gematik.zeta.sdk.clientregistration.model.SoftwareClientRegistrationRequest
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import kotlin.time.Clock

@Suppress("UnusedPrivateProperty")
class ClientRegistrationHandler(
    private val api: ClientRegistrationApi,
    private val postureProvider: PostureProvider,
) : CapabilityHandler {

    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.ClientRegistration

    override suspend fun handle(
        need: FlowNeed,
        ctx: FlowContext,
    ): CapabilityResult {
        val response = when (platform()) {
            is Platform.Jvm -> registerSoftwareClient()
            else -> TODO("has to be implemented")
        }

        ClientRegistrationStorage(ctx.storage)
            .saveClientId(response.clientId, response.clientIssuedAt)

        return CapabilityResult.Done
    }

    private suspend fun registerSoftwareClient(): ClientRegistrationResponse {
        // TODO: clientName: 'Name of the client, chosen by user or application'. Does it come from SDK?
        val clientName = ""
        val posture = postureProvider.buildSoftwarePosture()
        val payload = SoftwareClientRegistrationRequest(clientName, platform = SOFTWARE, posture)

        // TODO: uncomment when endpoint is available.
        // [A_27799](02): POST client registration
        // return api.register(payload)
        return ClientRegistrationResponse("test_client_id", Clock.System.now().epochSeconds)
    }
}
