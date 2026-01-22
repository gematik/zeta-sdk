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

package de.gematik.zeta.sdk.authentication

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.SdkStorage

interface AuthenticationStorage {
    suspend fun saveAccessTokens(
        fqdn: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    )

    suspend fun getAccessToken(fqdn: String): String?
    suspend fun getRefreshToken(fqdn: String): String?
    suspend fun getTokenExpiration(fqdn: String): String?
    suspend fun clear()
}

class AuthenticationStorageImpl(
    private val storage: SdkStorage,
) : AuthenticationStorage {
    private val extendedStorage: ExtendedStorage = ExtendedStorage(storage)
    companion object {
        private const val ACCESS_TOKEN_PREFIX = "at:"
        private const val REFRESH_TOKEN_PREFIX = "rt:"
        private const val TOKEN_EXPIRES_AT_PREFIX = "exp:"
        private const val HASH_INDEX_KEY = "hash_index"
    }

    private fun accessKey(hash: String) = "$ACCESS_TOKEN_PREFIX$hash"
    private fun refreshKey(hash: String) = "$REFRESH_TOKEN_PREFIX$hash"
    private fun expiresKey(hash: String) = "$TOKEN_EXPIRES_AT_PREFIX$hash"

    override suspend fun saveAccessTokens(
        fqdn: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
    ) {
        val hash = extendedStorage.registerHash(HASH_INDEX_KEY, fqdn)

        storage.put(accessKey(hash), accessToken)
        storage.put(refreshKey(hash), refreshToken)
        storage.put(expiresKey(hash), expiresAt.toString())
    }

    override suspend fun getAccessToken(fqdn: String): String? =
        storage.get(accessKey(extendedStorage.hash(fqdn)))

    override suspend fun getRefreshToken(fqdn: String): String? =
        storage.get(refreshKey(extendedStorage.hash(fqdn)))

    override suspend fun getTokenExpiration(fqdn: String): String? =
        storage.get(expiresKey(extendedStorage.hash(fqdn)))

    override suspend fun clear() {
        Log.d { "Removing all auth tokens" }

        extendedStorage
            .getHashes(HASH_INDEX_KEY)
            .forEach { hash ->
                storage.remove(accessKey(hash))
                storage.remove(refreshKey(hash))
                storage.remove(expiresKey(hash))
            }

        storage.remove(HASH_INDEX_KEY)
    }
}
