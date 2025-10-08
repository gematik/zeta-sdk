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

import de.gematik.zeta.sdk.configuration.ConfigurationApi
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.WellKnownTypes
import de.gematik.zeta.sdk.configuration.models.OidcDiscoveryResponse
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed

// TODO: Remove the suppress and fix errors
@Suppress("UnusedParameter", "UnusedPrivateProperty")
class ConfigurationHandler(
    private val configurationApi: ConfigurationApi,
) : CapabilityHandler {
    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.ConfigurationFiles

    override suspend fun handle(
        need: FlowNeed,
        ctx: FlowContext,
    ): CapabilityResult {
        if (!shallRefreshWellKnown()) return CapabilityResult.Done

        val authJson = configurationApi.fetchAuthorizationMetadata()
        if (validateAuthorizationMetadata(authJson)) {
            ConfigurationStorage(ctx.storage).saveAuthorizationServers(authJson)
        } else {
            throw ConfigurationError.ValidationField("Failed to validate ${WellKnownTypes.AUTHORIZATION_METADATA}")
        }

        val resourceJson = configurationApi.fetchResourceMetadata()
        if (validateResourceMetadata(resourceJson)) {
            ConfigurationStorage(ctx.storage).saveProtectedResources(resourceJson)
        } else {
            throw ConfigurationError.ValidationField("Failed to validate ${WellKnownTypes.RESOURCE_METADATA}")
        }

        return CapabilityResult.Done
    }

    // TODO: cache decision implementation
    @Suppress("FunctionOnlyReturningConstant")
    private fun shallRefreshWellKnown(): Boolean = true

    private suspend fun validateResourceMetadata(resourceJson: String): Boolean {
        val validation = configurationApi.getResourceSchema()
        // TODO: perform validation using validation using WellKnownSchemaValidation
        return true
    }

    private suspend fun validateAuthorizationMetadata(authJson: OidcDiscoveryResponse): Boolean {
        val validation = configurationApi.getAuthorizationSchema()
        // TODO: perform validation using WellKnownSchemaValidation
        return true
    }

    sealed class ConfigurationError(message: String) : RuntimeException(message) {
        class ValidationField(message: String) : ConfigurationError(message)
    }
}
