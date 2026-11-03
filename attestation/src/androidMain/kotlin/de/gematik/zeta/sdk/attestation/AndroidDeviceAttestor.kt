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
import de.gematik.zeta.sdk.tpm.AndroidKeyAliases
import de.gematik.zeta.sdk.tpm.AndroidKeyInvalidated
import de.gematik.zeta.sdk.tpm.AndroidKeyStoreBridge
import de.gematik.zeta.sdk.tpm.generateHardwareEcKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

private const val MIN_CHAIN_SIZE = 3
private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * Android Key Attestation implementation of [DeviceAttestor]. Owns the attestation key `Alias_AK`
 * (stored as the key id); reaches AndroidKeyStore only through [AndroidKeyStoreBridge].
 */
@Suppress("TooGenericExceptionCaught")
class AndroidDeviceAttestor(
    private val state: AttestationStateStorage,
    private val bridge: AndroidKeyStoreBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playIntegrity: PlayIntegrity = PlayIntegrity.Disabled,
) : DeviceAttestor {

    private val mutex = Mutex()

    override val attestationType: HardwareAttestationType = HardwareAttestationType.ANDROID

    override fun isSupported(): Boolean = try {
        bridge.hasHardwareKeystore()
    } catch (e: Exception) {
        Log.w(e) { "AndroidKeyStore unavailable; hardware attestation disabled" }
        false
    }

    override suspend fun attest(pukClientSig: ByteArray): DeviceAttestationEvidence = withContext(dispatcher) {
        mutex.withLock {
            if (!isSupported()) {
                Log.w { "Android Key Attestation is not supported on this device" }
                throw AndroidAttestationUnsupported()
            }
            // Abb-ZETA-Schlüsselgenerierung-Android (05) hash_puk_client_sig = SHA-256(PuK.Client.Sig)
            val hashPukClientSig = sha256PublicKeyPoint(pukClientSig)
            val aliasAk = AndroidKeyAliases.ATTESTATION_KEY
            val keyAttestationCertificateChain = try {
                val keyAttestationCertificateChain = generateAttestationKey(aliasAk, hashPukClientSig)
                state.setKeyId(aliasAk)
                state.setCertificateChain(keyAttestationCertificateChain)
                state.removeZetaAttestationToken()
                Log.i { "Android Key Attestation succeeded" }
                keyAttestationCertificateChain
            } catch (e: CancellationException) {
                deleteKey(aliasAk)
                throw e
            } catch (e: Exception) {
                state.removeAttestedKey()
                deleteKey(aliasAk)
                throw e as? AndroidAttestationException ?: AndroidAttestationKeystoreFailure(e)
            }
            // Abb-ZETA-Schlüsselgenerierung-Android (14) (15) play_integrity_token with nonce = hash_puk_client_sig
            val playIntegrityToken = playIntegrity.requestIntegrityToken(nonce = base64Url.encode(hashPukClientSig))
            DeviceAttestationEvidence.Android(keyAttestationCertificateChain, playIntegrityToken)
        }
    }

    override suspend fun runtimeEvidence(keyId: String, clientDataHash: ByteArray): ByteArray =
        signWithAttestationKey(clientDataHash, expectedKeyId = keyId)

    // Abb-ZETA-DCR-für-mobile-Clients (20) attestation_pop = Signature.sign(Alias_AK, SHA-256(PuK.Client.Sig))
    override suspend fun proveAttestationKey(hashPukClientSig: ByteArray): ByteArray =
        signWithAttestationKey(hashPukClientSig, expectedKeyId = null)

    override suspend fun hasAttestedKey(): Boolean {
        val alias = storedAttestationKeyAlias() ?: return false
        return state.hasCertificateChain() &&
            withContext(dispatcher) {
                try {
                    bridge.contains(alias)
                } catch (e: Exception) {
                    Log.w(e) { "AndroidKeyStore lookup failed" }
                    false
                }
            }
    }

    override suspend fun forget() {
        withContext(dispatcher) {
            mutex.withLock { storedAttestationKeyAlias()?.let(::deleteKey) }
        }
    }

    private suspend fun signWithAttestationKey(hash: ByteArray, expectedKeyId: String?): ByteArray = withContext(dispatcher) {
        mutex.withLock {
            val keyId = storedAttestationKeyAlias()
                ?: throw AndroidAttestationInvalidKey(IllegalStateException("No Android attestation key id"))
            if (expectedKeyId != null && expectedKeyId != keyId) {
                throw AndroidAttestationInvalidKey(IllegalStateException("Android attestation key id changed during the request"))
            }
            try {
                bridge.signSha256(keyId, hash)
            } catch (e: AndroidKeyInvalidated) {
                state.removeAttestedKey()
                deleteKey(keyId)
                Log.w(e) { "Android attestation key unusable; discarded it, the next registration attests a fresh key" }
                throw AndroidAttestationInvalidKey(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AndroidAttestationKeystoreFailure(e)
            }
        }
    }

    private fun generateAttestationKey(aliasAk: String, hashPukClientSig: ByteArray): List<ByteArray> {
        // Abb-ZETA-Schlüsselgenerierung-Android (06) (07) generate Alias_AK with attestationChallenge = hash_puk_client_sig
        if (!generateHardwareEcKey(bridge = bridge, alias = aliasAk, attestationChallenge = hashPukClientSig)) {
            throw AndroidAttestationUnsupported(IllegalStateException("Attestation key is not hardware-backed"))
        }
        // Abb-ZETA-Schlüsselgenerierung-Android (08) (09) android_key_attestation_certificate_chain
        val keyAttestationCertificateChain = bridge.certificateChain(aliasAk)
        if (keyAttestationCertificateChain == null || keyAttestationCertificateChain.size < MIN_CHAIN_SIZE) {
            throw AndroidAttestationUnsupported(IllegalStateException("Attestation chain has ${keyAttestationCertificateChain?.size ?: 0} certificates"))
        }
        return keyAttestationCertificateChain
    }

    private suspend fun storedAttestationKeyAlias(): String? =
        state.getKeyId()?.takeIf { it == AndroidKeyAliases.ATTESTATION_KEY }

    private fun deleteKey(alias: String) {
        try {
            bridge.delete(alias)
        } catch (e: Exception) {
            Log.w(e) { "Failed to delete $alias" }
        }
    }
}
