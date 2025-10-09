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

package de.gematik.zeta.sdk.authentication.model

import io.ktor.http.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_expires_in") val refreshExpires: Int,
    @SerialName("token_type") val tokenType: String,
    @SerialName("not-before-policy") val notBeforePolicy: String,
    @SerialName("session_state") val sessionState: String,
    @SerialName("scope") val scope: String,
    @SerialName("issued_token_type") val issuedTokenType: String,
)

fun AccessTokenRequest.toParameters() = Parameters.build {
    append("grant_type", grantType)
    append("client_id", clientId)
    append("subject_token", subjectToken)
    append("subject_token_type", subjectTokenType)
    append("scope", scope)
    append("requested_token_type", requestedTokenType)
    append("client_assertion_type", clientAssertionType)
    append("client_assertion", clientAssertion)
}
