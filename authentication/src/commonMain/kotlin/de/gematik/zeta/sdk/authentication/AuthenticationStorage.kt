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
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage

interface AuthenticationStorage {
    suspend fun saveAccessTokens(accessToken: String, refreshToken: String, expiresAt: Long, refreshExpiresAt: Long? = null)
    suspend fun getAccessToken(): String?
    suspend fun getRefreshToken(): String?
    suspend fun getAccessTokenExpiration(): String?
    suspend fun getRefreshTokenExpiration(): String?
    suspend fun clearAccessToken()
    suspend fun clear()
}

class AuthenticationStorageImpl(
    storage: SdkStorage,
    resourceScope: ResourceScope,
) : AuthenticationStorage {
    private val extended = ExtendedStorage(storage, resourceScope)

    companion object {
        const val ENTRY_KEY = "auth_token"
        const val PREFIX_ACCESS = "at"
        const val PREFIX_REFRESH = "rt"
        const val PREFIX_ACCESS_EXPIRES = "exp"
        const val PREFIX_REFRESH_EXPIRES = "ref_exp"
        const val INDEX_KEY = "auth_token_index"
    }

    override suspend fun saveAccessTokens(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        refreshExpiresAt: Long?,
    ) {
        extended.putIndexed(
            INDEX_KEY, ENTRY_KEY,
            mapOf(
                PREFIX_ACCESS to accessToken,
                PREFIX_REFRESH to refreshToken,
                PREFIX_ACCESS_EXPIRES to expiresAt.toString(),
                PREFIX_REFRESH_EXPIRES to (refreshExpiresAt?.toString() ?: ""),
            ),
        )
    }

    override suspend fun getAccessToken(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_ACCESS)
    override suspend fun getRefreshToken(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_REFRESH)
    override suspend fun getAccessTokenExpiration(): String? = extended.getIndexed(ENTRY_KEY, PREFIX_ACCESS_EXPIRES)
    override suspend fun getRefreshTokenExpiration(): String? =
        extended.getIndexed(ENTRY_KEY, PREFIX_REFRESH_EXPIRES)?.takeIf { it.isNotEmpty() }

    override suspend fun clearAccessToken() {
        Log.d { "Removing access token, keeping refresh token" }
        extended.removeIndexed(INDEX_KEY, ENTRY_KEY, listOf(PREFIX_ACCESS, PREFIX_ACCESS_EXPIRES))
    }

    override suspend fun clear() {
        Log.d { "Removing all auth tokens" }
        extended.clearIndexed(INDEX_KEY, ENTRY_KEY, listOf(PREFIX_ACCESS, PREFIX_REFRESH, PREFIX_ACCESS_EXPIRES, PREFIX_REFRESH_EXPIRES))
    }
}
