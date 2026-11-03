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

package de.gematik.zeta.sdk.attestation

/** Google Play Integrity token source; `null` means "send the request without play_integrity_token". */
interface PlayIntegrity {
    /**
     * Classic request (IntegrityManager.requestIntegrityToken), token bound via `nonce`.
     * For rare high-value actions: 10,000 requests/day per app, 5/min per device; nonce is URL-safe Base64, 16–500 chars.
     */
    suspend fun requestIntegrityToken(nonce: String): String?

    /**
     * Standard request (StandardIntegrityTokenProvider.request), token bound via `requestHash`.
     * For frequent requests: one-time provider warm-up (prepareIntegrityToken, a few seconds, max 5/min), then fast; requestHash max 500 chars.
     */
    suspend fun requestStandardToken(requestHash: String): String?

    /** Used when no cloud project number is configured. */
    object Disabled : PlayIntegrity {
        override suspend fun requestIntegrityToken(nonce: String): String? = null
        override suspend fun requestStandardToken(requestHash: String): String? = null
    }
}
