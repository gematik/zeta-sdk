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
 * Parameters for [NotificationApiClientImpl]'s bounded automatic retry on `429 Too Many Requests`.
 *
 * Per A_25339 the ZETA Client retries overload responses with exponential backoff (the
 * parameters are the ZETA-Client manufacturer's choice — these defaults are that choice), and
 * per A_27007 it honors the server's waiting-time hint: the wait between attempts is
 * `max(retry_after_ms from the 429 body, current backoff)`, with the backoff doubling per
 * attempt starting at [initialBackoffMs].
 *
 * Once [maxRetries] retries are exhausted, the final [NotificationRateLimitedException] is
 * rethrown so the host app decides the user-facing behavior — automatic retries never mask
 * sustained rate limiting. Set [maxRetries] to `0` to disable automatic retries entirely.
 */
data class RateLimitRetryPolicy(
    val maxRetries: Int = 2,
    val initialBackoffMs: Long = 500,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(initialBackoffMs >= 0) { "initialBackoffMs must be >= 0" }
    }
}
