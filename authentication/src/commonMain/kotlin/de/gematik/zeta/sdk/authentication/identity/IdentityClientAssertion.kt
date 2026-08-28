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

package de.gematik.zeta.sdk.authentication.identity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class IdentityClientAssertionHeader(
    @SerialName("alg") val alg: String,
    @SerialName("typ") val typ: String,
    @SerialName("kid") val kid: String,
)

@Serializable
internal data class IdentityClientAssertionClaims(
    @SerialName("iss") val iss: String,
    @SerialName("sub") val sub: String,
    @SerialName("aud") val aud: String,
    @SerialName("jti") val jti: String,
    @SerialName("iat") val iat: Long,
    @SerialName("exp") val exp: Long,
    @SerialName("htm") val htm: String,
    @SerialName("htu") val htu: String,
)
