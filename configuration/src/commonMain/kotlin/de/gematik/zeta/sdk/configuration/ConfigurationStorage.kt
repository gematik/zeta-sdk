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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.configuration.models.OidcDiscoveryResponse
import de.gematik.zeta.sdk.storage.SdkStorage

@Suppress("UnusedPrivateProperty")
class ConfigurationStorage(private val storage: SdkStorage) {
    companion object Companion {
        private const val RESOURCE_METADATA = "protected_resources"
        private const val AUTHORIZATION_METADATA = "authorization_server"
    }

    private var authorizationServers: OidcDiscoveryResponse? = null
    private var protectedResources: String = ""

    suspend fun saveAuthorizationServers(authServer: OidcDiscoveryResponse) {
        Log.d { "Saving authorization resources well-known file" }
        authorizationServers = authServer
        // storage.put(AUTHORIZATION_METADATA, authServer)
    }

    suspend fun saveProtectedResources(protectedRes: String) {
        Log.d { "Saving protected resources well-known file" }
        protectedResources = protectedRes
        // storage.put(RESOURCE_METADATA, protectedResources)
    }

    suspend fun getAuthorizationServers() = authorizationServers
    suspend fun getProtectedResources() = protectedResources

    suspend fun clear() {
        Log.d { "Removing protected resources and authorization servers at from storage" }
    }
}
