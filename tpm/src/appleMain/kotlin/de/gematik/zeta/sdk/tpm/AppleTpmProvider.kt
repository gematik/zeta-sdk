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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Apple [TpmProvider] that resolves the Client Instance Key implementation on demand,
 * preferring Secure Enclave when available.
 */
internal class AppleTpmProvider(
    private val hardwareClientKey: SecureEnclaveClientInstanceKey,
    private val softwareDelegate: SoftwareCryptoProvider,
    private val appAttestSupported: Boolean,
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

    override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut =
        resolveClientKeyMode().publicKey

    override suspend fun signWithClientKey(input: ByteArray): ByteArray =
        when (resolveClientKeyMode()) {
            is ClientKeyMode.Hardware -> hardwareClientKey.signWithClientKey(input)
            is ClientKeyMode.Software -> softwareDelegate.signWithClientKey(input)
        }

    override suspend fun signProofOfPossession(): ByteArray =
        when (resolveClientKeyMode()) {
            is ClientKeyMode.Hardware -> hardwareClientKey.signProofOfPossession()
            is ClientKeyMode.Software ->
                error(
                    "Proof-of-Possession is only available for hardware-attested (Secure Enclave) " +
                        "client keys; the software fallback registers via OIDC DCR without a PoP.",
                )
        }

    private suspend fun resolveClientKeyMode(): ClientKeyMode {
        clientKeyMode?.let { return it }
        return resolveMutex.withLock {
            clientKeyMode ?: run {
                val point = if (appAttestSupported) hardwareClientKey.resolvePublicKeyPoint() else null
                val resolved = if (point != null) {
                    ClientKeyMode.Hardware(hardwareClientKey.toPublicKeyOut(point))
                } else {
                    ClientKeyMode.Software(softwareDelegate.getOrGenerateClientInstancePublicKey())
                }
                clientKeyMode = resolved
                resolved
            }
        }
    }

    override suspend fun forget(resource: String?) {
        if (resource == null) {
            resolveMutex.withLock {
                clientKeyMode = null
                hardwareClientKey.delete()
            }
        }
        softwareDelegate.forget(resource)
    }
}
