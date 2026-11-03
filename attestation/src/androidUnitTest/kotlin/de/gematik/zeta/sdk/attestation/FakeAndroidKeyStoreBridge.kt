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

import de.gematik.zeta.sdk.tpm.AndroidKeyInvalidated
import de.gematik.zeta.sdk.tpm.AndroidKeyStoreBridge
import de.gematik.zeta.sdk.tpm.KeySecurityLevel
import de.gematik.zeta.sdk.tpm.StrongBoxUnavailable
import de.gematik.zeta.sdk.tpm.ecPublicKeyToPoint
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

internal val THREE_CERT_CHAIN = listOf(byteArrayOf(0x0A), byteArrayOf(0x0B), byteArrayOf(0x0C))

internal fun newEcKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

internal class FakeAndroidKeyStoreBridge(
    var hardwareKeystore: Boolean = true,
    var strongBoxAvailable: Boolean = true,
    var forcedLevel: KeySecurityLevel? = null,
    var chain: List<ByteArray>? = THREE_CERT_CHAIN,
    var throwOnHasHardware: Boolean = false,
    var throwOnDelete: Boolean = false,
) : AndroidKeyStoreBridge {
    val keys = mutableMapOf<String, KeyPair>()
    val challenges = mutableMapOf<String, ByteArray?>()
    val generateCalls = mutableListOf<Pair<String, Boolean>>()
    val invalidated = mutableSetOf<String>()

    override fun hasHardwareKeystore(): Boolean {
        if (throwOnHasHardware) error("keystore unavailable")
        return hardwareKeystore
    }

    override fun generateEcKey(alias: String, attestationChallenge: ByteArray?, strongBox: Boolean): KeySecurityLevel {
        generateCalls += alias to strongBox
        if (strongBox && !strongBoxAvailable) throw StrongBoxUnavailable()
        keys[alias] = newEcKeyPair()
        challenges[alias] = attestationChallenge
        return forcedLevel ?: if (strongBox) KeySecurityLevel.STRONGBOX else KeySecurityLevel.TRUSTED_ENVIRONMENT
    }

    override fun publicKeyPoint(alias: String) = keys[alias]?.let { ecPublicKeyToPoint(it.public as ECPublicKey) }
    override fun certificateChain(alias: String): List<ByteArray>? = if (alias in keys) chain else null
    override fun signSha256(alias: String, data: ByteArray) = sign(alias, "SHA256withECDSA", data)
    override fun contains(alias: String) = alias in keys
    override fun delete(alias: String) {
        if (throwOnDelete) error("keystore delete failed")
        keys.remove(alias)
    }

    private fun sign(alias: String, algorithm: String, input: ByteArray): ByteArray {
        if (alias in invalidated) throw AndroidKeyInvalidated(null)
        val pair = keys[alias] ?: throw AndroidKeyInvalidated(null)
        return Signature.getInstance(algorithm).run {
            initSign(pair.private)
            update(input)
            sign()
        }
    }
}
