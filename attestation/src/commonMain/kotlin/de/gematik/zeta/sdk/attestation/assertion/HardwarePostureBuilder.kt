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
package de.gematik.zeta.sdk.attestation.assertion

import de.gematik.zeta.sdk.attestation.DeviceAttestor
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.attestation.model.PostureType
import kotlinx.serialization.json.JsonElement

class HardwarePosture(val postureType: PostureType, val posture: JsonElement)

/**
 * Produces the hardware-backed runtime posture for the client statement (gemSpec_ZETA A_29809, A_29810).
 * Returns `null` only when the registration is not hardware-attested; for a hardware-attested
 * registration it returns the posture or throws.
 */
fun interface HardwarePostureBuilder {
    suspend fun build(
        pukClientSig: ByteArray,
        nonce: ByteArray,
        platformProductId: PlatformProductId,
        productId: String,
        productVersion: String,
    ): HardwarePosture?
}

class HardwareAttestation(val attestor: DeviceAttestor, val postureBuilder: HardwarePostureBuilder)
