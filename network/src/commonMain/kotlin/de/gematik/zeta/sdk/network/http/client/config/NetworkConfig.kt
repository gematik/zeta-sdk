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

package de.gematik.zeta.sdk.network.http.client.config

import io.ktor.http.HttpStatusCode

/**
 * Low-level transport and retry behavior.
 *
 * @property connectionTimeoutMillis Max time to establish a TCP/TLS connection (ms).
 * @property requestTimeoutMillis    Overall time limit per request including body (ms).
 * @property retryStatusCodes        HTTP statuses that should be retried (e.g., 502/503/504).
 *                                   Empty set = do not retry on status codes.
 * @property maxRetries              Max number of retries (0 disables all retry logic).
 * @property retryOnlyIdempotent     If true, only retry idempotent methods (GET/HEAD/PUT/DELETE).
 */
internal data class NetworkConfig(
    val connectionTimeoutMillis: Long = 15_000,
    val requestTimeoutMillis: Long = 30_000,
    val retryStatusCodes: Set<HttpStatusCode> = emptySet(),
    val maxRetries: Int = 0,
    val retryOnlyIdempotent: Boolean = true,
)
