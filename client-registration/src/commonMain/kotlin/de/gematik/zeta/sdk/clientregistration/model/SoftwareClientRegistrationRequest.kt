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
data class SoftwareClientRegistrationRequest(
    /* Name of the client, chosen by user or application */
    val name: String,
    val platform: Platform,
    val posture: SoftwarePosture,
)

@Serializable
data class ClientRegistrationResponse(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_issued_at") val clientIssuedAt: Long,
)

@Serializable
enum class Platform {
    @SerialName("android") ANDROID,

    @SerialName("apple") APPLE,

    @SerialName("software") SOFTWARE,
}

@Serializable
data class SoftwarePosture(
    /* Operating system name*/
    val os: String,

    /* Operating system version */
    @SerialName("os_version") val osVersion: String,

    /* Hardware Architecture */
    val arch: String,

    /* The public self signed signing key (PEM or base64 DER encoded) */
    @SerialName("public_key") val publicKey: String,

    /* The attestation challenge of the client instance, used to verify the public client instance key and the nonce from AS. */
    @SerialName("attestation_challenge") val attestationChallenge: String,
)
