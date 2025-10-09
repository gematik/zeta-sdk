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
import de.gematik.zeta.sdk.storage.SdkStorage

class ClientRegistrationStorage(private val storage: SdkStorage) {
    companion object Companion {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_ISSUED_AT = "client_issued_at"
    }

    suspend fun saveClientId(clientId: String, issuedAt: Long) {
        Log.d { "Saving client id and client issued at" }
        storage.put(KEY_CLIENT_ID, clientId)
        storage.put(KEY_CLIENT_ISSUED_AT, issuedAt.toString())
    }

    suspend fun getClientId() = storage.get(KEY_CLIENT_ID)
    suspend fun getClientIssuedAt() = storage.get(KEY_CLIENT_ISSUED_AT)

    suspend fun clear() {
        Log.d { "Removing client id and client issued at from storage" }
        storage.remove(KEY_CLIENT_ID)
        storage.remove(KEY_CLIENT_ISSUED_AT)
    }
}
