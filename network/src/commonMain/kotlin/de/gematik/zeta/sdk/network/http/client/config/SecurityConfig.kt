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

import de.gematik.zeta.sdk.network.http.client.DEFAULT_REVOCATION_CACHE_SECONDS

/**
 * TLS / trust configuration.
 *
 * @property additionalCaPem Extra CA certificates in PEM format appended to the platform trust store.
 * @property additionalCaFile Path to a file containing additional CA certificates in PEM format.
 * @property disableServerValidation Disables server certificate and hostname validation.
 *                                   Must only be used in test environments. Defaults to false.
 * @property sslVerbose Enables verbose SSL/TLS logging for debugging. Defaults to false.
 * @property revocationCacheDurationSeconds Maximum duration in seconds a cached OCSP/CRL response
 *                                          is used, counted from the moment it was stored. A
 *                                          response is used while now() is before
 *                                          min(nextUpdate, validatedAt + this duration).
 */
public data class SecurityConfig(
    val additionalCaPem: List<String> = emptyList(),
    val additionalCaFile: String? = null,
    val disableServerValidation: Boolean = false,
    val sslVerbose: Boolean = false,
    val revocationCacheDurationSeconds: Long = DEFAULT_REVOCATION_CACHE_SECONDS,
) {
    init {
        require(revocationCacheDurationSeconds >= DEFAULT_REVOCATION_CACHE_SECONDS) {
            "revocationCacheDurationSeconds must be at least 3600s (1h), was $revocationCacheDurationSeconds"
        }
    }
}
