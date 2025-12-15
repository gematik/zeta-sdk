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
data class DPoPTokenClaims(
    /** Issued-at time (Unix epoch seconds) */
    @SerialName("iat") val iat: Long,
    /** JWT ID */
    @SerialName("jti") val jti: String,
    /** The HTTP method of the request to which the DPoP proof is being attached */
    @SerialName("htm") val htm: String,
    /** The HTTP URI of the request, excluding any query and fragment parts */
    @SerialName("htu") val htu: String,
    /** A recent nonce provided by the server to prevent replay attacks */
    @SerialName("nonce") val nonce: String? = null,
    /** The base64url-encoded SHA-256 hash of the associated access token. Required when the DPoP proof is used in conjunction with an access token. */
    @SerialName("ath") val ath: String? = null,
)
