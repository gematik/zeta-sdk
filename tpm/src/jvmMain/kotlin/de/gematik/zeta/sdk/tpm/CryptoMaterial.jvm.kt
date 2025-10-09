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
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

// TODO: logic to determine if hardware backed is available
@Suppress("FunctionOnlyReturningConstant")
internal fun hardwareBackedAvailable(): Boolean = false
internal actual fun platformDefaultProvider(): CryptoProvider {
    if (hardwareBackedAvailable()) {
        Log.d { "Using hardware crypto provider (JVM)" }
        TODO("hardware backed provider")
    } else {
        Log.d { "Using software crypto provider (JVM)" }
        return SoftwareCryptoProvider()
    }
}

@Suppress("UnusedPrivateProperty")
private class SoftwareKeyHandle(
    private val privateKey: PrivateKey,
    override val publicKey: ByteArray,
) : KeyHandle {
    override suspend fun signDigest(digest: ByteArray): ByteArray {
        TODO("has to be implemented")
    }
}

private class SoftwareCryptoProvider : CryptoProvider {
    override val isHardwareBacked: Boolean = false
    private lateinit var longLivedClientKeyPair: KeyHandle
    private val sessionDPoPKey: KeyHandle? = null
    private fun ecKpg(): KeyPairGenerator =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }

    private fun ensureClientKey(): KeyHandle =
        sessionDPoPKey ?: ecKpg().generateKeyPair().let { SoftwareKeyHandle(it.private, it.public.encoded) }

    override suspend fun generateClientInstanceKey(): ByteArray {
        val kp = ensureClientKey()
        return kp.publicKey
    }

    override suspend fun generateDpopKey(): ByteArray {
        val kp = ecKpg().generateKeyPair()
        longLivedClientKeyPair = SoftwareKeyHandle(kp.private, kp.public.encoded)
        return longLivedClientKeyPair.publicKey
    }

    override suspend fun signDigestWithClientKey(digest: ByteArray): ByteArray {
        return longLivedClientKeyPair.signDigest(digest)
    }

    override suspend fun signDigestWithDpopKey(digest: ByteArray): ByteArray {
        return sessionDPoPKey?.signDigest(digest) ?: error("generateDpopKeyHandle() has never been called.")
    }

    override fun hash(input: ByteArray, alg: HashAlg): ByteArray =
        when (alg) {
            HashAlg.SHA256 -> MessageDigest.getInstance("SHA-256").digest(input)
        }
}
