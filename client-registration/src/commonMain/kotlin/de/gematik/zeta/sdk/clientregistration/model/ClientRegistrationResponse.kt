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

package de.gematik.zeta.sdk.clientregistration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientRegistrationResponse(
    @SerialName("redirect_uris") val redirectUris: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
    @SerialName("grant_types") val grantTypes: List<String> = emptyList(),
    @SerialName("response_types") val responseTypes: List<String> = emptyList(),
    @SerialName("client_id") var clientId: String, // TODO: change to val when registration endpoint works
    @SerialName("client_secret") val clientSecret: String? = null,
    @SerialName("client_name") val clientName: String? = null,
    val scope: String? = null,
    val jwks: Jwks? = null,
    @SerialName("subject_type") val subjectType: String? = null,
    @SerialName("request_uris") val requestUris: List<String> = emptyList(),
    @SerialName("tls_client_certificate_bound_access_tokens") val tlsClientCertBoundAccessTokens: Boolean? = null,
    @SerialName("dpop_bound_access_tokens") val dpopBoundAccessTokens: Boolean? = null,
    @SerialName("post_logout_redirect_uris") val postLogoutRedirectUris: List<String> = emptyList(),
    @SerialName("client_id_issued_at") val clientIdIssuedAt: Long? = null,
    @SerialName("client_secret_expires_at") val clientSecretExpiresAt: Long? = null,
    @SerialName("registration_client_uri") val registrationClientUri: String? = null,
    @SerialName("registration_access_token") val registrationAccessToken: String? = null,
    @SerialName("backchannel_logout_session_required") val backchannelLogoutSessionRequired: Boolean? = null,
    @SerialName("require_pushed_authorization_requests") val requirePushedAuthorizationRequests: Boolean? = null,
    @SerialName("frontchannel_logout_session_required") val frontchannelLogoutSessionRequired: Boolean? = null,
)
