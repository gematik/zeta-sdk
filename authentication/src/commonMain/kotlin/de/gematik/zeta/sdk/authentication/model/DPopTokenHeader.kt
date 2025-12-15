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

import AsymAlg
import Jwk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DPopTokenHeader(
    /** Token type where "at": access token and "rt": refresh token  */
    @SerialName("typ") val typ: TokenType,
    /** SHA-256 hash of the public signing key used  */
    @SerialName("kid") val kid: String? = null,
    /** SMC-B Public Signer Key  */
    @SerialName("jwk") val jwk: Jwk,
    /** Algorithm  */
    @SerialName("alg") val alg: AsymAlg,
)
