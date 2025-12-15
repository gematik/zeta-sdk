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
data class AccessTokenHeader(
    /** The type of the JWT.  */
    @SerialName("typ") val typ: TokenType,
    /** SHA-256 hash of the certificate used  */
    @SerialName("kid") val kid: String? = null,
    /** Contains the certificate. The certificate must be the leaf certificate containing the public key for verifying the signature. It must be base64-der-encoded */
    @SerialName("x5c") val x5c: List<String>,
    /** The algorithm used to sign the JWT.  */
    @SerialName("alg") val alg: String,
)
