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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccessTokenClaims(
    /** Issuer */
    @SerialName("iss") val issuer: String,
    /** Expiration time (Unix epoch seconds) */
    @SerialName("exp") val exp: Long,
    /** Audiences */
    @SerialName("aud") val audience: List<String>,
    /** Subject */
    @SerialName("sub") val subject: String,
    /** Issued-at time (Unix epoch seconds) */
    @SerialName("iat") val iat: Long,
    /** JWT ID */
    @SerialName("jti") val jti: String,
    /** Optional space-separated scopes (per OAuth2) */
    @SerialName("scope") val scope: String? = null,
    /** Confirmation claim with DPoP thumbprint */
    @SerialName("cnf") val cnf: Cnf,
) {
    @Serializable
    data class Cnf(
        /** SHA-256 hash (base64url) of the DPoP public key */
        @SerialName("jkt") val jkt: String,
    )
}
