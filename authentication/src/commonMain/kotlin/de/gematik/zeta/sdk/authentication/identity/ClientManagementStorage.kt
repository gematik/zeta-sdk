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

package de.gematik.zeta.sdk.authentication.identity

import de.gematik.zeta.sdk.authentication.AuthenticationStorage

internal class ClientManagementStorage(
    private val delegate: AuthenticationStorage,
) : AuthenticationStorage {

    override suspend fun saveAccessTokens(accessToken: String, refreshToken: String, expiresAt: Long, refreshExpiresAt: Long?) {
        delegate.saveAccessTokens(accessToken, NO_REFRESH_TOKEN, expiresAt, null)
    }

    override suspend fun getAccessToken(): String? = delegate.getAccessToken()
    override suspend fun getRefreshToken(): String? = null
    override suspend fun getAccessTokenExpiration(): String? = delegate.getAccessTokenExpiration()
    override suspend fun getRefreshTokenExpiration(): String? = null
    override suspend fun clearAccessToken() = delegate.clearAccessToken()
    override suspend fun clear() = delegate.clear()

    private companion object {
        const val NO_REFRESH_TOKEN = ""
    }
}
