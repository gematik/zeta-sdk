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

package de.gematik.zeta.sdk.notifications

import de.gematik.zeta.sdk.authentication.AuthenticationStorage

/**
 * Process-local [AuthenticationStorage] holding the single Notification Service access token for
 * one SDK instance, isolated from the primary session token. It lets
 * [ReauthNotificationTokenProvider] reuse the standard issuance path
 * ([de.gematik.zeta.sdk.authentication.AccessTokenProvider.getValidToken]) without any change to
 * the authentication module: `getValidToken` reads/writes its token through this store instead of
 * the shared session entry.
 *
 * The NS token is deliberately **not** persisted — it is a short-lived derivative of the session
 * (re-minted via full re-authentication when absent), so keeping it in memory avoids a second
 * persistent [de.gematik.zeta.sdk.storage.ResourceScope] and any logout-clearing plumbing.
 *
 * [getRefreshToken] always returns `null` so `getValidToken` never attempts a refresh grant: this
 * guard mints `aud`-less tokens on refresh grants, so a refreshed NS token would be rejected by the
 * PEP. Expiry therefore forces a fresh full issuance. The refresh token from an issuance response
 * is dropped for the same reason.
 */
internal class NotificationTokenStorage : AuthenticationStorage {
    private var accessToken: String? = null
    private var expiresAt: Long? = null

    override suspend fun saveAccessTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        this.accessToken = accessToken
        this.expiresAt = expiresAt
    }

    override suspend fun getAccessToken(): String? = accessToken
    override suspend fun getRefreshToken(): String? = null
    override suspend fun getTokenExpiration(): String? = expiresAt?.toString()
    override suspend fun clearAccessToken() = clear()
    override suspend fun clear() {
        accessToken = null
        expiresAt = null
    }
}
