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
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApi
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationException
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.tpm.TpmProvider
import kotlinx.coroutines.delay

@Suppress("UnusedPrivateProperty")
class ClientRegistrationHandler(
    private val clientName: String,
    private val regApi: ClientRegistrationApi,
    private val tpmProvider: TpmProvider,
    private val maxRetries: Int = 3,
) : CapabilityHandler {
    companion object {
        private const val REGISTRATION_FAILED_ERROR = "REGISTRATION_FAILED_ERROR"
    }
    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.ClientRegistration

    override suspend fun handle(
        need: FlowNeed,
        ctx: FlowContext,
    ): CapabilityResult {
        val authServer = ctx.configurationStorage.getAuthServer(ctx.resource)
        checkNotNull(authServer) { "Failed to load authorization server metadata from storage" }

        val clientId = ctx.clientRegistrationStorage.getClientId(authServer.issuer)
        if (!clientId.isNullOrBlank()) {
            return CapabilityResult.Done
        }

        val registrationResponse = attemptRegister(authServer.openidProvidersEndpoint)

        val response = registrationResponse.getOrElse { exception ->
            exception as ClientRegistrationException

            return CapabilityResult.Error(
                REGISTRATION_FAILED_ERROR,
                exception.message.toString(),
                exception.response,
            )
        }

        ctx
            .clientRegistrationStorage
            .saveRegistration(authServer.issuer, response)

        return CapabilityResult.Done
    }

    private suspend fun attemptRegister(endpoint: String): Result<ClientRegistrationResponse> {
        var lastException: ClientRegistrationException? = null

        repeat(maxRetries) { attempt ->
            try {
                val request = ClientRegistrationRequest(
                    tokenEndpointAuthMethod = "private_key_jwt",
                    grantTypes = listOf("urn:ietf:params:oauth:grant-type:token-exchange", "refresh_token"),
                    responseTypes = listOf("token"),
                    clientName = clientName,
                    jwks = Jwks(listOf(tpmProvider.generateClientInstanceKey().jwk)),
                )

                val response = regApi.register(endpoint, request)

                return Result.success(response)
            } catch (ex: ClientRegistrationException) {
                lastException = ex
                Log.w { "Attempt $attempt registration failed with ${ex.response.status.value}: ${ex.message}" }

                if (ex.response.status.value in listOf(403, 409)) {
                    return Result.failure(lastException)
                }

                if (attempt == maxRetries - 1) {
                    Log.e { "Registration failed after $attempt attempts with ${ex.response.status.value}: ${ex.message}" }
                    return Result.failure(lastException)
                }
            }

            delay(1000L * (attempt + 1))
        }
        return Result.failure(lastException ?: Exception("Unexpected exception during registration"))
    }
}
