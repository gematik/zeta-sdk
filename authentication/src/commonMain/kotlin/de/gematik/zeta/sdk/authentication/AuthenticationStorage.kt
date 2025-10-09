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
import de.gematik.zeta.sdk.storage.SdkStorage

class AuthenticationStorage(private val storage: SdkStorage) {
    companion object Companion {
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val TOKEN_EXPIRES_IN = "token_expiration"
    }

    suspend fun saveAccessTokens(accessToken: String, expiresIn: Int) {
        Log.d { "Saving auth. access token and expiration" }
        storage.put(ACCESS_TOKEN, accessToken)
        storage.put(TOKEN_EXPIRES_IN, expiresIn.toString())
    }

    suspend fun getAccessToken() = storage.get(ACCESS_TOKEN)
    suspend fun getRefreshToken() = storage.get(REFRESH_TOKEN)
    suspend fun getTokenExpiration() = storage.get(TOKEN_EXPIRES_IN)

    suspend fun clear() {
        Log.d { "Removing access token, refresh token and expiration from storage" }
        storage.remove(ACCESS_TOKEN)
        storage.remove(REFRESH_TOKEN)
        storage.remove(TOKEN_EXPIRES_IN)
    }
}
