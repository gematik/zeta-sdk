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

package de.gematik.zeta.sdk.network.http.client.config.tls
public object ZetaCipherSuites {
    public const val ECDHE_ECDSA_AES128_GCM_SHA256: String = "ECDHE-ECDSA-AES128-GCM-SHA256"
    public const val ECDHE_ECDSA_AES256_GCM_SHA384: String = "ECDHE-ECDSA-AES256-GCM-SHA384"
    public const val ECDHE_RSA_AES128_GCM_SHA256: String = "ECDHE-RSA-AES128-GCM-SHA256"
    public const val ECDHE_RSA_AES256_GCM_SHA384: String = "ECDHE-RSA-AES256-GCM-SHA384"

    public const val AES_128_GCM_SHA256: String = "TLS_AES_128_GCM_SHA256"
    public const val AES_256_GCM_SHA384: String = "TLS_AES_256_GCM_SHA384"

    public val REQUIRED_TLS_1_2: List<String> = listOf(
        ECDHE_ECDSA_AES128_GCM_SHA256,
        ECDHE_ECDSA_AES256_GCM_SHA384,
        ECDHE_RSA_AES128_GCM_SHA256,
        ECDHE_RSA_AES256_GCM_SHA384,
    )

    public val TLS_1_3_SUITES: List<String> = listOf(
        AES_128_GCM_SHA256,
        AES_256_GCM_SHA384,
    )

    public val FULL_PREFERRED_ORDER: List<String> = REQUIRED_TLS_1_2 + TLS_1_3_SUITES

    internal val OPENSSL_TO_IANA: Map<String, String> = mapOf(
        ECDHE_ECDSA_AES128_GCM_SHA256 to "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        ECDHE_ECDSA_AES256_GCM_SHA384 to "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        ECDHE_RSA_AES128_GCM_SHA256 to "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        ECDHE_RSA_AES256_GCM_SHA384 to "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        AES_128_GCM_SHA256 to "TLS_AES_128_GCM_SHA256",
        AES_256_GCM_SHA384 to "TLS_AES_256_GCM_SHA384",
    )
    public val FULL_PREFERRED_ORDER_IANA: List<String> =
        FULL_PREFERRED_ORDER.map { OPENSSL_TO_IANA[it] ?: it }
}
