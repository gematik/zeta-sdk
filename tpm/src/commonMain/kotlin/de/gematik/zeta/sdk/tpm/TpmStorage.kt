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

package de.gematik.zeta.sdk.tpm

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.storage.SdkStorage

interface TpmStorage {
    suspend fun saveClientKeys(publicKey: String, privateKey: String)
    suspend fun saveDpopKeys(publicKey: String, privateKey: String)
    suspend fun getClientPublicKey(): String?
    suspend fun getClientPrivateKey(): String?
    suspend fun getDpopPublicKey(): String?
    suspend fun getDpopPrivateKey(): String?
    suspend fun clear()
}

class TpmStorageImpl(private val storage: SdkStorage) : TpmStorage {
    companion object Companion {
        private const val CLIENT_PUBLIC_KEY = "client_public_key"
        private const val CLIENT_PRIVATE_KEY = "client_private_key"
        private const val DPOP_PUBLIC_KEY = "dpop_public_key"
        private const val DPOP_PRIVATE_KEY = "dpop_private_key"
    }

    override suspend fun saveClientKeys(publicKey: String, privateKey: String) {
        Log.d { "Saving client keys" }
        storage.put(CLIENT_PUBLIC_KEY, publicKey)
        storage.put(CLIENT_PRIVATE_KEY, privateKey)
    }

    override suspend fun saveDpopKeys(publicKey: String, privateKey: String) {
        Log.d { "Saving client keys" }
        storage.put(DPOP_PUBLIC_KEY, publicKey)
        storage.put(DPOP_PRIVATE_KEY, privateKey)
    }

    override suspend fun getClientPublicKey(): String? = storage.get(CLIENT_PUBLIC_KEY)
    override suspend fun getClientPrivateKey(): String? = storage.get(CLIENT_PRIVATE_KEY)
    override suspend fun getDpopPublicKey(): String? = storage.get(DPOP_PUBLIC_KEY)
    override suspend fun getDpopPrivateKey(): String? = storage.get(DPOP_PRIVATE_KEY)

    override suspend fun clear() {
        Log.d { "Removing tpm key" }
        storage.remove(CLIENT_PUBLIC_KEY)
        storage.remove(CLIENT_PRIVATE_KEY)
        storage.remove(DPOP_PUBLIC_KEY)
        storage.remove(DPOP_PRIVATE_KEY)
    }
}
