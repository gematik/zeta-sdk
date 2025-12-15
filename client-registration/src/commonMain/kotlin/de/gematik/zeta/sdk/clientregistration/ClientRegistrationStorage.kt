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

package de.gematik.zeta.sdk.clientregistration

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.network.http.client.hostOf
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.json.Json

interface ClientRegistrationStorage {
    suspend fun saveRegistration(authServer: String, registrationResponse: ClientRegistrationResponse)
    suspend fun getClientId(authServer: String): String?
    suspend fun clear()
}

class ClientRegistrationStorageImpl(private val sdkStorage: SdkStorage) : ClientRegistrationStorage {
    companion object Companion {
        private const val CLIENT_REGISTRATION_BY_AUTH_SERVER_KEY = "client_registration_by_auth_server"
    }
    private val storage = ExtendedStorage(sdkStorage)

    override suspend fun saveRegistration(authServer: String, registrationResponse: ClientRegistrationResponse) {
        Log.d { "Saving client registration for AS: $authServer" }
        val key = hostOf(authServer)
        val map = storage.getMap(CLIENT_REGISTRATION_BY_AUTH_SERVER_KEY) ?: mutableMapOf()
        map[key] = Json.encodeToString(registrationResponse)

        storage.putMap(CLIENT_REGISTRATION_BY_AUTH_SERVER_KEY, map)
    }

    override suspend fun getClientId(authServer: String): String? {
        val key = hostOf(authServer)
        val map = storage.getMap(CLIENT_REGISTRATION_BY_AUTH_SERVER_KEY) ?: return null
        val raw = map[key] ?: return null

        return runCatching { Json.decodeFromString<ClientRegistrationResponse>(raw).clientId }
            .onFailure { e -> Log.e { "Failed to decode registration for $key: ${e.message}" } }
            .getOrNull()
    }

    override suspend fun clear() {
        Log.d { "Removing client registration" }
        storage.remove(CLIENT_REGISTRATION_BY_AUTH_SERVER_KEY)
    }
}
