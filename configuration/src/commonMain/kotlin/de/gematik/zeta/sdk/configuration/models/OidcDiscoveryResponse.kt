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
data class OidcDiscoveryResponse(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("introspection_endpoint") val introspectionEndpoint: String? = null,
    @SerialName("userinfo_endpoint") val userinfoEndpoint: String? = null,
    @SerialName("end_session_endpoint") val endSessionEndpoint: String? = null,
    @SerialName("jwks_uri") val jwksUri: String,
    @SerialName("check_session_iframe") val checkSessionIframe: String? = null,
    @SerialName("grant_types_supported") val grantTypesSupported: List<String> = emptyList(),
    @SerialName("response_types_supported")
    val responseTypesSupported: List<String> = emptyList(),

    @SerialName("subject_types_supported")
    val subjectTypesSupported: List<String> = emptyList(),

    @SerialName("id_token_signing_alg_values_supported")
    val idTokenSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("id_token_encryption_alg_values_supported")
    val idTokenEncryptionAlgValuesSupported: List<String> = emptyList(),

    @SerialName("id_token_encryption_enc_values_supported")
    val idTokenEncryptionEncValuesSupported: List<String> = emptyList(),

    @SerialName("userinfo_signing_alg_values_supported")
    val userinfoSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("userinfo_encryption_alg_values_supported")
    val userinfoEncryptionAlgValuesSupported: List<String> = emptyList(),

    @SerialName("userinfo_encryption_enc_values_supported")
    val userinfoEncryptionEncValuesSupported: List<String> = emptyList(),

    @SerialName("request_object_signing_alg_values_supported")
    val requestObjectSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("request_object_encryption_alg_values_supported")
    val requestObjectEncryptionAlgValuesSupported: List<String> = emptyList(),

    @SerialName("request_object_encryption_enc_values_supported")
    val requestObjectEncryptionEncValuesSupported: List<String> = emptyList(),

    @SerialName("response_modes_supported")
    val responseModesSupported: List<String> = emptyList(),

    @SerialName("registration_endpoint")
    val registrationEndpoint: String? = null,

    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String> = emptyList(),

    @SerialName("token_endpoint_auth_signing_alg_values_supported")
    val tokenEndpointAuthSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("introspection_endpoint_auth_methods_supported")
    val introspectionEndpointAuthMethodsSupported: List<String> = emptyList(),

    @SerialName("introspection_endpoint_auth_signing_alg_values_supported")
    val introspectionEndpointAuthSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("authorization_signing_alg_values_supported")
    val authorizationSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("authorization_encryption_alg_values_supported")
    val authorizationEncryptionAlgValuesSupported: List<String> = emptyList(),

    @SerialName("authorization_encryption_enc_values_supported")
    val authorizationEncryptionEncValuesSupported: List<String> = emptyList(),

    @SerialName("claims_supported")
    val claimsSupported: List<String> = emptyList(),

    @SerialName("claim_types_supported")
    val claimTypesSupported: List<String> = emptyList(),

    @SerialName("claims_parameter_supported")
    val claimsParameterSupported: Boolean? = null,

    @SerialName("scopes_supported")
    val scopesSupported: List<String> = emptyList(),

    @SerialName("request_parameter_supported")
    val requestParameterSupported: Boolean? = null,

    @SerialName("request_uri_parameter_supported")
    val requestUriParameterSupported: Boolean? = null,

    @SerialName("require_request_uri_registration")
    val requireRequestUriRegistration: Boolean? = null,

    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String> = emptyList(),

    @SerialName("tls_client_certificate_bound_access_tokens")
    val tlsClientCertificateBoundAccessTokens: Boolean? = null,

    @SerialName("revocation_endpoint")
    val revocationEndpoint: String? = null,

    @SerialName("revocation_endpoint_auth_methods_supported")
    val revocationEndpointAuthMethodsSupported: List<String> = emptyList(),

    @SerialName("revocation_endpoint_auth_signing_alg_values_supported")
    val revocationEndpointAuthSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("backchannel_logout_supported")
    val backchannelLogoutSupported: Boolean? = null,

    @SerialName("backchannel_logout_session_supported")
    val backchannelLogoutSessionSupported: Boolean? = null,

    @SerialName("device_authorization_endpoint")
    val deviceAuthorizationEndpoint: String? = null,

    @SerialName("backchannel_token_delivery_modes_supported")
    val backchannelTokenDeliveryModesSupported: List<String> = emptyList(),

    @SerialName("backchannel_authentication_endpoint")
    val backchannelAuthenticationEndpoint: String? = null,

    @SerialName("backchannel_authentication_request_signing_alg_values_supported")
    val backchannelAuthenticationRequestSigningAlgValuesSupported: List<String> = emptyList(),

    @SerialName("require_pushed_authorization_requests")
    val requirePushedAuthorizationRequests: Boolean? = null,

    @SerialName("pushed_authorization_request_endpoint")
    val pushedAuthorizationRequestEndpoint: String? = null,

    @SerialName("mtls_endpoint_aliases")
    val mtlsEndpointAliases: Map<String, String>? = null,

    @SerialName("authorization_response_iss_parameter_supported")
    val authorizationResponseIssParameterSupported: Boolean? = null,
)
