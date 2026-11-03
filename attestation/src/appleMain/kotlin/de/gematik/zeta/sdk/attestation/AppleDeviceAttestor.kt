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

package de.gematik.zeta.sdk.attestation

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.sha256PublicKeyPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

/**
 * Apple App Attest implementation of [DeviceAttestor], covering iOS, iPadOS and macOS.
 *
 * It owns the App Attest protocol and the lifecycle of the attestation key, and reaches the platform
 * only through [AppAttestBridge].
 */
internal class AppleDeviceAttestor(
    private val state: AttestationStateStorage,
    private val bridge: AppAttestBridge,
    private val supported: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DeviceAttestor {

    private val mutex = Mutex()

    override val attestationType: HardwareAttestationType = HardwareAttestationType.APPLE

    override fun isSupported(): Boolean = supported()

    override suspend fun attest(pukClientSig: ByteArray): DeviceAttestationEvidence = withContext(dispatcher) {
        mutex.withLock {
            if (!supported()) {
                Log.w { "App Attest is not supported on this device" }
                throw AppAttestUnsupported()
            }
            val challenge = sha256PublicKeyPoint(pukClientSig)
            val keyId = bridge.generateKey()
            val attestationObject = bridge.attestKey(keyId, challenge)
            state.setKeyId(keyId)
            state.setAttestationObject(Base64.encode(attestationObject))
            state.removeZetaAttestationToken()
            Log.i { "App Attest attestation succeeded" }
            DeviceAttestationEvidence.Apple(keyId, attestationObject)
        }
    }

    override suspend fun runtimeEvidence(keyId: String, clientDataHash: ByteArray): ByteArray =
        assertion(clientDataHash, expectedKeyId = keyId)

    override suspend fun proveAttestationKey(hashPukClientSig: ByteArray): ByteArray = assertion(hashPukClientSig, expectedKeyId = null)

    override suspend fun hasAttestedKey(): Boolean = state.getKeyId() != null

    // App Attest keys cannot be deleted.
    override suspend fun forget() = Unit

    private suspend fun assertion(clientDataHash: ByteArray, expectedKeyId: String?): ByteArray = withContext(dispatcher) {
        mutex.withLock {
            val keyId = state.getKeyId()
                ?: throw AppAttestAttestationFailed(IllegalStateException("No App Attest keyId"))
            if (expectedKeyId != null && expectedKeyId != keyId) {
                throw AppAttestInvalidKey(IllegalStateException("App Attest keyId changed during the request"))
            }
            try {
                bridge.generateAssertion(keyId, clientDataHash)
            } catch (e: AppAttestServerUnavailable) {
                Log.w(e) { "App Attest server unavailable; keeping the attested key for a later retry" }
                throw e
            } catch (e: AppAttestException) {
                state.removeAttestedKey()
                Log.w(e) { "App Attest key unusable; discarded keyId, the next registration attests a fresh key" }
                throw e
            }
        }
    }
}
