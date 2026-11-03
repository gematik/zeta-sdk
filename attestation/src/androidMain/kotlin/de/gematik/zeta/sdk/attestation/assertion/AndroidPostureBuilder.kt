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
import de.gematik.zeta.sdk.attestation.AndroidAttestationInvalidKey
import de.gematik.zeta.sdk.attestation.AndroidPostureCollector
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.DeviceAttestor
import de.gematik.zeta.sdk.attestation.PlayIntegrity
import de.gematik.zeta.sdk.attestation.RegisteredAttestationType
import de.gematik.zeta.sdk.attestation.model.AndroidPosture
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.attestation.model.PostureType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** Android runtime posture signed by the attestation key (A_29809). */
class AndroidPostureBuilder(
    private val attestor: DeviceAttestor,
    private val state: AttestationStateStorage,
    private val collector: AndroidPostureCollector,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playIntegrity: PlayIntegrity = PlayIntegrity.Disabled,
) : HardwarePostureBuilder {

    override suspend fun build(
        pukClientSig: ByteArray,
        nonce: ByteArray,
        platformProductId: PlatformProductId,
        productId: String,
        productVersion: String,
    ): HardwarePosture? {
        if (state.getAttestationType() != RegisteredAttestationType.ANDROID) {
            Log.d { "Registration is not Android-attested; using software posture" }
            return null
        }
        val platformProduct = checkNotNull(platformProductId as? PlatformProductId.AndroidProductId) {
            "Android-attested registration requires an Android product id, got ${platformProductId.platform}"
        }
        val keyId = state.getKeyId()
        if (keyId == null || !attestor.hasAttestedKey()) {
            throw AndroidAttestationInvalidKey(IllegalStateException("No attested Android attestation key"))
        }
        val device = withContext(dispatcher) { collector.collect() }
        val posture = AndroidPosture(
            platformProductId = platformProduct,
            productId = productId,
            productVersion = productVersion,
            build = device.build,
            ro = device.ro,
            packageManager = device.packageManager,
            keyguardManager = device.keyguardManager,
            biometricManager = device.biometricManager,
            devicePolicyManager = device.devicePolicyManager,
            nonce = base64Url.encode(nonce),
        )
        val postureBytes = hardwarePostureJson.encodeToString(AndroidPosture.serializer(), posture).encodeToByteArray()
        // Client-Statement-mit-Android-Attestation (05) clientDataHash = SHA-256(nonce || posture || PuK.Client.Sig)
        val clientDataHash = computeClientDataHash(nonce, postureBytes, pukClientSig)
        // Client-Statement-mit-Android-Attestation (06)-(09) signed_client_data_hash = Signature.sign(Alias_AK, clientDataHash)
        val signedClientDataHash = attestor.runtimeEvidence(keyId, clientDataHash)
        // Client-Statement-mit-Android-Attestation (10) (11) integrityToken with requestHash = clientDataHash
        val playIntegrityToken = playIntegrity.requestStandardToken(requestHash = base64Url.encode(clientDataHash))
        // Client-Statement-mit-Android-Attestation (12) client-statement: posture data in plain text + signed_client_data_hash + play_integrity_token; android_key_attestation_certificate_chain omitted (posture-android 2.0.0)
        val clientStatementPosture = posture.copy(
            signedClientDataHash = base64Url.encode(signedClientDataHash),
            playIntegrityToken = playIntegrityToken,
        )
        return HardwarePosture(PostureType.ANDROID, hardwarePostureJson.encodeToJsonElement(AndroidPosture.serializer(), clientStatementPosture))
    }
}
