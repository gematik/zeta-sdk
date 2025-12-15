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
data class AuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @SerialName("token_endpoint")
    val tokenEndpoint: String,
    @SerialName("nonce_endpoint")
    val nonceEndpoint: String,
    @SerialName("openid_providers_endpoint")
    val openidProvidersEndpoint: String,
    @SerialName("jwks_uri")
    val jwksUri: String,
    @SerialName("scopes_supported")
    val scopesSupported: List<String>,
    @SerialName("response_types_supported")
    val responseTypesSupported: List<String>,
    @SerialName("response_modes_supported")
    val responseModesSupported: List<String>? = null,
    @SerialName("grant_types_supported")
    val grantTypesSupported: List<String>,
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String>,
    @SerialName("token_endpoint_auth_signing_alg_values_supported")
    val tokenEndpointAuthSigningAlgValuesSupported: List<String>,
    @SerialName("service_documentation")
    val serviceDocumentation: String? = null,
    @SerialName("ui_locales_supported")
    val uiLocalesSupported: List<String>,
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>,
    @SerialName("api_versions_supported")
    val apiVersionsSupported: List<ApiVersion>? = null,
)

@Serializable
data class ApiVersion(
    @SerialName("major_version")
    val majorVersion: Int,
    val version: String,
    val status: ApiVersionStatus,
    @SerialName("documentation_uri")
    val documentationUri: String? = null,
)

@Serializable
enum class ApiVersionStatus {
    @SerialName("stable") STABLE,

    @SerialName("beta") BETA,

    @SerialName("alpha") ALPHA,

    @SerialName("deprecated") DEPRECATED,

    @SerialName("retired") RETIRED,
}
