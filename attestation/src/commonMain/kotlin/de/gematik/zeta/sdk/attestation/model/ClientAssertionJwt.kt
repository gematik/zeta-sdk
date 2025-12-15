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

package de.gematik.zeta.sdk.attestation.model

import Jwk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ClientAssertionJwt(
    val header: Header,
    val payload: Payload,
) {
    @Serializable
    data class Header(
        val typ: String,
        val alg: String,
        val jwk: Jwk,
    )

    @Serializable
    data class Payload(
        val iss: String, // client_id
        val sub: String, // client_id
        val aud: List<String>, // token url endpoints
        val exp: Long, // epoch seconds
        val jti: String, // unique id

        @SerialName("client_statement")
        val clientStatement: JsonElement? = null,
    ) {
        init {
            require(iss.isNotEmpty()) { "Payload iss must be not blank" }
            require(sub.isNotEmpty()) { "Payload sub must be not blank" }
            require(aud.isNotEmpty()) { "Payload sub must be not blank" }
            require(aud.all { it.isNotBlank() }) { "Payload aud entries must not be non blank base64 string" }
            require(jti.isNotEmpty()) { "Payload jti must be not blank" }
        }
    }
}
