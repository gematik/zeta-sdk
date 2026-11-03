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

import de.gematik.zeta.logging.Log
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

object AndroidKeyAliases {
    const val CLIENT_INSTANCE_KEY = "Alias_Client_Sig"
    const val ATTESTATION_KEY = "Alias_AK"
}

enum class KeySecurityLevel { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX }

class StrongBoxUnavailable(cause: Throwable? = null) : Exception("StrongBox is not available on this device", cause)

class AndroidKeyInvalidated(cause: Throwable?) : Exception("Android Keystore key is invalidated or missing", cause)

/**
 * Thin seam over AndroidKeyStore. Implementations map framework exceptions to [StrongBoxUnavailable]
 * and [AndroidKeyInvalidated]. Signatures are DER ECDSA.
 */
interface AndroidKeyStoreBridge {
    fun hasHardwareKeystore(): Boolean
    fun generateEcKey(alias: String, attestationChallenge: ByteArray?, strongBox: Boolean): KeySecurityLevel
    fun publicKeyPoint(alias: String): ByteArray?
    fun certificateChain(alias: String): List<ByteArray>?
    fun signSha256(alias: String, data: ByteArray): ByteArray
    fun contains(alias: String): Boolean
    fun delete(alias: String)
}

/** StrongBox first, TEE second; returns false when only a software-backed key could be created (it is deleted). */
fun generateHardwareEcKey(
    bridge: AndroidKeyStoreBridge,
    alias: String,
    attestationChallenge: ByteArray?,
): Boolean {
    val level = try {
        bridge.generateEcKey(alias, attestationChallenge, strongBox = true)
    } catch (e: StrongBoxUnavailable) {
        Log.i { "StrongBox unavailable for $alias; generating the key in the TEE" }
        bridge.generateEcKey(alias, attestationChallenge, strongBox = false)
    }
    if (level == KeySecurityLevel.SOFTWARE) {
        Log.w { "Key $alias is not hardware-backed; discarding it" }
        bridge.delete(alias)
        return false
    }
    Log.i { "Key $alias generated in $level" }
    return true
}

private const val COORDINATE_SIZE = 32

fun ecPublicKeyToPoint(key: ECPublicKey): ByteArray =
    byteArrayOf(0x04) + key.w.affineX.toFixed() + key.w.affineY.toFixed()

private fun BigInteger.toFixed(): ByteArray {
    val bytes = toByteArray()
    return when {
        bytes.size == COORDINATE_SIZE -> bytes
        bytes.size > COORDINATE_SIZE -> bytes.copyOfRange(bytes.size - COORDINATE_SIZE, bytes.size)
        else -> ByteArray(COORDINATE_SIZE - bytes.size) + bytes
    }
}
