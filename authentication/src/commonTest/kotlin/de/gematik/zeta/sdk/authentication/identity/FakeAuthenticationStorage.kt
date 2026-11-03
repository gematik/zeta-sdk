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

internal class FakeAuthenticationStorage : AuthenticationStorage {
    var accessToken: String? = null
    var refreshToken: String? = null
    var expiresAt: Long? = null
    var refreshExpiresAt: Long? = null
    var accessTokenClears = 0
        private set
    var clears = 0
        private set

    override suspend fun saveAccessTokens(accessToken: String, refreshToken: String, expiresAt: Long, refreshExpiresAt: Long?) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = expiresAt
        this.refreshExpiresAt = refreshExpiresAt
    }

    override suspend fun getAccessToken(): String? = accessToken
    override suspend fun getRefreshToken(): String? = refreshToken
    override suspend fun getAccessTokenExpiration(): String? = expiresAt?.toString()
    override suspend fun getRefreshTokenExpiration(): String? = refreshExpiresAt?.toString()

    override suspend fun clearAccessToken() {
        accessTokenClears++
        accessToken = null
        expiresAt = null
    }

    override suspend fun clear() {
        clears++
        accessToken = null
        refreshToken = null
        expiresAt = null
        refreshExpiresAt = null
    }
}
