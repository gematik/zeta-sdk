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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.AppAttestInvalidKey
import de.gematik.zeta.sdk.attestation.AppleDeviceInfo
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.DeviceAttestor
import de.gematik.zeta.sdk.attestation.RegisteredAttestationType
import de.gematik.zeta.sdk.attestation.model.ApplePosture
import de.gematik.zeta.sdk.attestation.model.AssertionAuthenticatorData
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.attestation.model.PostureType
import kotlin.io.encoding.Base64

fun interface ApplePostureCollector {
    fun collect(): AppleDeviceInfo
}

/**
 * Apple runtime assertion posture (A_29810 steps 03–10).
 */
class AppleAssertionPostureBuilder(
    private val attestor: DeviceAttestor,
    private val state: AttestationStateStorage,
    private val collector: ApplePostureCollector,
) : HardwarePostureBuilder {

    override suspend fun build(
        pukClientSig: ByteArray,
        nonce: ByteArray,
        platformProductId: PlatformProductId,
        productId: String,
        productVersion: String,
    ): HardwarePosture? {
        if (state.getAttestationType() != RegisteredAttestationType.APPLE) {
            Log.d { "Registration is not Apple-attested; using software posture" }
            return null
        }
        val appleProductId = checkNotNull(platformProductId as? PlatformProductId.AppleProductId) {
            "Apple-attested registration requires an Apple product id, got ${platformProductId.platform}"
        }
        val keyId = state.getKeyId()
        if (keyId == null || !attestor.hasAttestedKey()) {
            throw AppAttestInvalidKey(IllegalStateException("No attested App Attest key"))
        }
        val attestationObject = state.getAttestationObject()
            ?: throw AppAttestInvalidKey(IllegalStateException("No stored App Attest attestation object"))
        val base = basePosture(appleProductId, productId, productVersion, keyId, decodeAttestationObject(Base64.decode(attestationObject)))
        val postureBytes = hardwarePostureJson.encodeToString(ApplePosture.serializer(), base).encodeToByteArray()
        val clientDataHash = computeClientDataHash(nonce, postureBytes, pukClientSig)
        val decoded = decodeDcAssertion(attestor.runtimeEvidence(keyId, clientDataHash))
        val full = base.copy(
            signature = Base64.encode(decoded.signature),
            assertionAuthenticatorData = AssertionAuthenticatorData(Base64.encode(decoded.rpIdHash), decoded.counter),
        )
        return HardwarePosture(PostureType.APPLE, hardwarePostureJson.encodeToJsonElement(ApplePosture.serializer(), full))
    }

    private fun basePosture(
        platformProductId: PlatformProductId.AppleProductId,
        productId: String,
        productVersion: String,
        keyId: String,
        attestation: DecodedAttestationObject,
    ): ApplePosture {
        val device = collector.collect()
        return ApplePosture(
            platformProductId = platformProductId,
            productId = productId,
            productVersion = productVersion,
            systemVersion = device.systemVersion,
            systemName = device.systemName,
            deviceModel = device.deviceModel,
            keyId = keyId,
            attStmt = attestation.statement(),
            authData = attestation.authData(),
        )
    }
}
