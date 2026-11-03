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

package de.gematik.zeta.sdk.tpm

import PublicKeyOut
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.p256UncompressedPointToJwk
import derEcdsaToJose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Client Instance Key `Alias_Client_Sig` in AndroidKeyStore (TEE/StrongBox) with the JVM software
 * provider as fallback and for every other key, analogous to `AppleTpmProvider`.
 */
class AndroidTpmProvider(
    private val bridge: AndroidKeyStoreBridge,
    private val softwareDelegate: TpmProvider,
    private val useHardwareClientKey: Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TpmProvider by softwareDelegate, ClientKeyPoPSigner {

    private sealed interface ClientKeyMode {
        val publicKey: PublicKeyOut
        class Hardware(override val publicKey: PublicKeyOut) : ClientKeyMode
        class Software(override val publicKey: PublicKeyOut) : ClientKeyMode
    }

    private val resolveMutex = Mutex()

    @Volatile
    private var clientKeyMode: ClientKeyMode? = null

    override suspend fun isHardwareBacked(): Boolean = resolveClientKeyMode() is ClientKeyMode.Hardware

    override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut = resolveClientKeyMode().publicKey

    override suspend fun signWithClientKey(input: ByteArray): ByteArray =
        when (resolveClientKeyMode()) {
            is ClientKeyMode.Hardware -> withContext(dispatcher) {
                derEcdsaToJose(bridge.signSha256(AndroidKeyAliases.CLIENT_INSTANCE_KEY, input), 32)
            }
            is ClientKeyMode.Software -> softwareDelegate.signWithClientKey(input)
        }

    override suspend fun signProofOfPossession(): ByteArray =
        when (val mode = resolveClientKeyMode()) {
            // Abb-ZETA-Schlüsselgenerierung-Android (10)-(13) signed_hash_puk_client_sig = Signature.sign(Alias_Client_Sig, hash_puk_client_sig)
            is ClientKeyMode.Hardware -> withContext(dispatcher) {
                val pukClientSig = mode.publicKey.encoded
                bridge.signSha256(AndroidKeyAliases.CLIENT_INSTANCE_KEY, pukClientSig)
            }
            is ClientKeyMode.Software ->
                error(
                    "Proof-of-Possession is only available for hardware-backed (AndroidKeyStore) " +
                        "client keys; the software fallback registers via OIDC DCR without a PoP.",
                )
        }

    private suspend fun resolveClientKeyMode(): ClientKeyMode {
        clientKeyMode?.let { return it }
        return resolveMutex.withLock {
            clientKeyMode ?: run {
                val pukClientSig = if (useHardwareClientKey) withContext(dispatcher) { hardwarePukClientSig() } else null
                if (pukClientSig != null) {
                    ClientKeyMode.Hardware(PublicKeyOut(pukClientSig, p256UncompressedPointToJwk(pukClientSig)))
                } else {
                    ClientKeyMode.Software(softwareDelegate.getOrGenerateClientInstancePublicKey())
                }
            }.also { clientKeyMode = it }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun hardwarePukClientSig(): ByteArray? = try {
        bridge.publicKeyPoint(AndroidKeyAliases.CLIENT_INSTANCE_KEY) ?: run {
            // Abb-ZETA-Schlüsselgenerierung-Android (01)-(04) KeyStore.generateKey(Alias_Client_Sig)
            if (!generateHardwareEcKey(bridge = bridge, alias = AndroidKeyAliases.CLIENT_INSTANCE_KEY, attestationChallenge = null)) return null
            bridge.publicKeyPoint(AndroidKeyAliases.CLIENT_INSTANCE_KEY)
        }
    } catch (e: Exception) {
        Log.w(e) { "AndroidKeyStore client key unavailable; using the software client key" }
        null
    }

    override suspend fun forget(resource: String?) {
        if (resource == null) {
            resolveMutex.withLock {
                clientKeyMode = null
                withContext(dispatcher) { deleteHardwareClientKey() }
            }
        }
        softwareDelegate.forget(resource)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteHardwareClientKey() {
        try {
            bridge.delete(AndroidKeyAliases.CLIENT_INSTANCE_KEY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(e) { "AndroidKeyStore unavailable while deleting the client key; ignoring" }
        }
    }
}
