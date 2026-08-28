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

/**
 * Supplies a valid, service-specific, DPoP-bound access token for calls to the Notification
 * Service. The matching per-request DPoP proof is supplied separately via
 * [NotificationDpopProvider].
 *
 * Callers of [NotificationApiClientImpl] depend only on this interface and must not know whether
 * [StaticNotificationTokenProvider] (injected/static token) or [ReauthNotificationTokenProvider]
 * (re-authentication against the ZETA Guard with the NS audience) supplies the token behind it.
 *
 * [requiredScopes] names the scopes the calling operation needs (least privilege, A_29979).
 * Providers may ignore it and hand out one multi-scope token; forwarding it enables per-scope
 * tokens without any change to [NotificationApiClientImpl].
 */
fun interface NotificationTokenProvider {
    suspend fun getAccessToken(requiredScopes: Set<String>): String

    /** Drops any cached token so the next [getAccessToken] re-acquires; default no-op. */
    suspend fun invalidate(requiredScopes: Set<String>) {
        // no-op: only caching providers override this
    }
}
