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

package de.gematik.zeta.sdk.configuration.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProtectedResourceMetadata(
    val resource: String,

    @SerialName("authorization_servers")
    val authorizationServers: List<String>,

    @SerialName("zeta_asl_use")
    val zetaAslUse: ZetaAslUse,

    @SerialName("jwks_uri")
    val jwksUri: String? = null,

    @SerialName("scopes_supported")
    val scopesSupported: List<String>? = null,

    @SerialName("bearer_methods_supported")
    val bearerMethodsSupported: List<BearerMethod>? = null,

    @SerialName("resource_signing_alg_values_supported")
    val resourceSigningAlgValuesSupported: List<String>? = null,

    @SerialName("resource_name")
    val resourceName: String? = null,

    @SerialName("resource_documentation")
    val resourceDocumentation: String? = null,

    @SerialName("resource_policy_uri")
    val resourcePolicyUri: String? = null,

    @SerialName("resource_tos_uri")
    val resourceTosUri: String? = null,

    @SerialName("tls_client_certificate_bound_access_tokens")
    val tlsClientCertificateBoundAccessTokens: Boolean = false,

    @SerialName("authorization_details_types_supported")
    val authorizationDetailsTypesSupported: List<String>? = null,

    @SerialName("dpop_signing_alg_values_supported")
    val dpopSigningAlgValuesSupported: List<String>? = null,

    @SerialName("dpop_bound_access_tokens_required")
    val dpopBoundAccessTokensRequired: Boolean = false,

    @SerialName("signed_metadata")
    val signedMetadata: String? = null,

    @SerialName("api_versions_supported")
    val apiVersionsSupported: List<ApiVersion>? = null,
)

@Serializable
enum class ZetaAslUse {
    @SerialName("not_supported") NOT_SUPPORTED,

    @SerialName("required") REQUIRED,
}

@Serializable
enum class BearerMethod {
    @SerialName("header") HEADER,

    @SerialName("body") BODY,

    @SerialName("query") QUERY,
}
