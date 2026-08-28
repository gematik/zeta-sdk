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
 * Creates the DPoP proof JWT (RFC 9449) that accompanies a single request to the Notification
 * Service. A fresh proof is required per request because it is bound to the request's HTTP
 * method ([htm]), its URL ([htu]), and — via the `ath` claim — to the access token it travels
 * with.
 *
 * [ZetaDpopNotificationProvider] is the production implementation (TPM-backed key, signed via
 * zeta-sdk's authentication stack); tests and simulator setups can substitute a stub, analogous
 * to [StaticNotificationTokenProvider] on the token seam.
 */
fun interface NotificationDpopProvider {
    /**
     * @param htm the HTTP method of the request, e.g. `"GET"`
     * @param htu the absolute request URL, without query or fragment
     * @param accessToken the access token the proof is bound to through its `ath` claim
     * @return the signed DPoP proof JWT for exactly this request
     */
    suspend fun createDpopProof(htm: String, htu: String, accessToken: String): String
}
