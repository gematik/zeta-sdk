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
import de.gematik.zeta.sdk.crypto.EcdhP256Kem
import de.gematik.zeta.sdk.crypto.EcdhSigner
import de.gematik.zeta.sdk.crypto.KeyPair
import de.gematik.zeta.sdk.crypto.X509PemReader
import derEcdsaToJose
import java.util.Base64
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

private class SoftwareCryptoProvider(
    storage: TpmStorage,
    private val keyPairGenerator: EcdhP256Kem,
    private val signer: EcdhSigner,
    x509PemReader: X509PemReader,
) : AbstractSoftwareCryptoProvider(storage, x509PemReader) {

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut {
        val start = TimeSource.Monotonic.markNow()
        if (clientKey == null) {
            val (loaded, loadTime) = measureTimedValue { loadClientKeysFromStorage() }
            clientKey = loaded
            Log.d { "[CRYPTO-TIMING] loadClientKeysFromStorage=$loadTime found=${loaded != null}" }
            if (clientKey == null) {
                val (generated, genTime) = measureTimedValue { keyPairGenerator.generateKeys() }
                clientKey = generated
                Log.d { "[CRYPTO-TIMING] generateClientKeys=$genTime" }
                val (_, saveTime) = measureTimedValue {
                    storage.saveClientKeys(
                        toPem("PUBLIC KEY", clientKey!!.skpi),
                        toPem("PRIVATE KEY", clientKey!!.privateKey),
                    )
                }
                Log.d { "[CRYPTO-TIMING] saveClientKeys=$saveTime" }
            }
        }
        val jwk = keyPairGenerator.toJwk(clientKey!!.skpi)
        Log.d { "[CRYPTO-TIMING] generateClientInstanceKey total=${start.elapsedNow()}" }
        return PublicKeyOut(clientKey!!.skpi, jwk)
    }

    override suspend fun generateDpopKey(): PublicKeyOut {
        val existing = loadDpopKeysFromStorage()
        val key = if (existing != null) {
            existing
        } else {
            val (generated, genTime) = measureTimedValue { keyPairGenerator.generateKeys() }
            Log.d { "[CRYPTO-TIMING] generateDpopKeys()=$genTime" }
            val (_, saveTime) = measureTimedValue {
                storage.saveDpopKeys(
                    toPem("PUBLIC KEY", generated.skpi),
                    toPem("PRIVATE KEY", generated.privateKey),
                )
            }
            Log.d { "[CRYPTO-TIMING] saveDpopKeys()=$saveTime" }
            generated
        }
        return PublicKeyOut(key.skpi, keyPairGenerator.toJwk(key.skpi))
    }

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun signWithClientKey(input: ByteArray): ByteArray {
        checkNotNull(clientKey) { "Client key not initialized" }
        val (result, signTime) = measureTimedValue { signForJws(clientKey!!.privateKey, input) }
        Log.d { "[CRYPTO-TIMING] signWithClientKey=$signTime inputSize=${input.size}" }
        return result
    }

    override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray {
        val key = checkNotNull(loadDpopKeysFromStorage()) {
            "DPoP key not found for resource: $resource"
        }
        val (result, signTime) = measureTimedValue { signForJws(key.privateKey, input) }
        Log.d { "[CRYPTO-TIMING] signWithDpopKey($resource)=$signTime inputSize=${input.size}" }
        return result
    }

    override suspend fun signSmb(privateKey: ByteArray, signingInput: ByteArray): ByteArray =
        signForJws(privateKey, signingInput)

    private suspend fun loadDpopKeysFromStorage(): KeyPair? {
        val privRaw = storage.getDpopPrivateKey() ?: return null
        val pubRaw = storage.getDpopPublicKey() ?: return null

        return try {
            keyPairGenerator.loadKeys(
                decodePem(decodeHexPem(privRaw)),
                decodePem(decodeHexPem(pubRaw)),
            )
        } catch (ex: Exception) {
            Log.d { "Failed to load DPoP keys for: ${ex.message}" }
            null
        }
    }

    private suspend fun loadClientKeysFromStorage(): KeyPair? {
        val privRaw = storage.getClientPrivateKey() ?: return null
        val pubRaw = storage.getClientPublicKey() ?: return null

        return try {
            keyPairGenerator.loadKeys(
                decodePem(decodeHexPem(privRaw)),
                decodePem(decodeHexPem(pubRaw)),
            )
        } catch (ex: Exception) {
            Log.d { "Failed to load client keys: ${ex.message}" }
            null
        }
    }

    private fun isHexString(s: String): Boolean =
        s.length % 2 == 0 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun decodeHexPem(s: String): String {
        if (!isHexString(s)) return s
        return try {
            val txt = hexToBytes(s).toString(Charsets.UTF_8)
            if (txt.startsWith("-----BEGIN ")) txt else s
        } catch (_: Exception) { s }
    }

    private fun decodePem(pem: String): ByteArray {
        val b64 = pem
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
        return Base64.getMimeDecoder().decode(b64)
    }

    private fun toPem(type: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    fun signForJws(privateKey: ByteArray, signingInput: ByteArray): ByteArray {
        val s = signer.sign(privateKey, signingInput)
        return derEcdsaToJose(s, 32)
    }
}

@Suppress("FunctionOnlyReturningConstant")
internal fun hardwareBackedAvailable(): Boolean = false

actual fun platformDefaultProvider(storage: TpmStorage, appAttestSupported: Boolean): TpmProvider {
    if (hardwareBackedAvailable()) {
        Log.d { "Using hardware crypto provider (JVM)" }
        TODO("hardware backed provider")
    } else {
        Log.d { "Using software crypto provider (JVM)" }
        return SoftwareCryptoProvider(storage, EcdhP256Kem(), EcdhSigner(), X509PemReader())
    }
}
