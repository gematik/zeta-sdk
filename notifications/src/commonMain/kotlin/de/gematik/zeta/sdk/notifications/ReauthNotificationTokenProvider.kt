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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Token and nonce endpoints of the guard Authorization Server linked to this instance. */
data class NotificationTokenEndpoints(
    val tokenEndpoint: String,
    val nonceEndpoint: String,
)

/** The Notification Service's token audience (resource id) and requested scopes, from discovery. */
data class NotificationTokenRequest(
    val audience: String,
    val scopes: Set<String>,
)

/**
 * Production [NotificationTokenProvider]: mints the Notification Service access token by re-running
 * the instance's primary authentication with the NS audience and scopes, against an isolated
 * in-memory store ([NotificationTokenStorage]) so the primary session token is never touched.
 *
 * This is **not** a new authentication mechanism — it drives the same
 * [AccessTokenProvider.getValidToken] issuance path the primary flow uses, only with
 * [AccessTokenParams.audience]/[AccessTokenParams.scopes] overridden. Initial-issuance grants honor
 * audience/scope (unlike refresh grants on this guard), so the NS token carries the correct `aud`.
 * The store reports no refresh token, so expiry forces a fresh full issuance rather than a broken
 * refresh grant.
 *
 * Consequence: on OIDC-mode clients each NS-token expiry triggers a browser round-trip (the OIDC
 * flow has no silent path); SMB is silent. This disappears once the guard supports A_29843 or RFC
 * 8693 token exchange, at which point only this provider is swapped — [NotificationTokenProvider]
 * and everything above it are unaffected.
 *
 * @param issuerFactory builds the issuance engine over the given (isolated) storage; the SDK facade
 *   injects an [AccessTokenProvider] construction so this module never sees the auth configuration.
 * @param resolveRequest supplies the NS audience/scopes, performing lazy NS discovery on first use.
 */
class ReauthNotificationTokenProvider(
    issuerFactory: (AuthenticationStorage) -> AccessTokenProvider,
    private val endpoints: suspend () -> NotificationTokenEndpoints,
    private val baseParams: suspend () -> AccessTokenParams,
    private val dpopKid: suspend () -> String,
    private val stepUp: suspend () -> Unit,
    private val resolveRequest: suspend () -> NotificationTokenRequest,
) : NotificationTokenProvider {

    private val store = NotificationTokenStorage()
    private val issuer = issuerFactory(store)
    private val mutex = Mutex()

    override suspend fun getAccessToken(requiredScopes: Set<String>): String = mutex.withLock {
        val request = resolveRequest()
        try {
            issue(request)
        } catch (e: RecoverableAuthenticationException) {
            Log.d { "NS token issuance failed recoverably (${e.message}), stepping up session" }
            stepUp()
            issue(request)
        }
    }

    override suspend fun invalidate(requiredScopes: Set<String>) = store.clear()

    private suspend fun issue(request: NotificationTokenRequest): String {
        val endpoints = endpoints()
        val params = baseParams().copy(scopes = request.scopes.sorted(), audience = request.audience)
        return issuer.getValidToken(endpoints.tokenEndpoint, endpoints.nonceEndpoint, params, dpopKid())
    }
}
