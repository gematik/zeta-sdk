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

import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApi
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.tpm.TpmProvider

@Suppress("UnusedPrivateProperty")
class ClientRegistrationHandler(
    private val clientName: String,
    private val regApi: ClientRegistrationApi,
    private val tpmProvider: TpmProvider,
) : CapabilityHandler {
    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.ClientRegistration

    override suspend fun handle(
        need: FlowNeed,
        ctx: FlowContext,
    ): CapabilityResult {
        val authServer = ctx.configurationStorage.getAuthServer(ctx.resource)
        val clientId = ctx.clientRegistrationStorage.getClientId(authServer!!.issuer)

        if (!clientId.isNullOrBlank()) {
            return CapabilityResult.Done
        }

        val registrationRequest = ClientRegistrationRequest(
            tokenEndpointAuthMethod = "private_key_jwt",
            grantTypes = listOf("urn:ietf:params:oauth:grant-type:token-exchange", "refresh_token"),
            responseTypes = listOf("token"),
            clientName = clientName,
            jwks = Jwks(listOf(tpmProvider.generateClientInstanceKey().jwk)),
        )

        val registrationResponse = regApi.register(authServer.openidProvidersEndpoint, registrationRequest)

        ctx.clientRegistrationStorage
            .saveRegistration(authServer.issuer, registrationResponse)

        return CapabilityResult.Done
    }
}
