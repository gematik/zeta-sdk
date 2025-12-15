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
import kotlin.uuid.Uuid

private class SoftwareCryptoProvider(
    private val storage: TpmStorage,
    private val keyPairGenerator: EcdhP256Kem,
    private val signer: EcdhSigner,
    private val x509PemReader: X509PemReader,
) : TpmProvider {
    override val isHardwareBacked: Boolean = false

    private var clientKey: KeyPair? = null
    private var dpopKey: KeyPair? = null

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun generateClientInstanceKey(): PublicKeyOut {
        if (clientKey == null) {
            clientKey = loadClientKeysFromStorage()
            if (clientKey == null) {
                clientKey = keyPairGenerator.generateKeys()
                storage.saveClientKeys(
                    toPem("PUBLIC KEY", clientKey!!.skpi),
                    toPem("PRIVATE KEY", clientKey!!.privateKey),
                )
            }
        }
        val jwk = keyPairGenerator.toJwk(clientKey!!.skpi)
        return PublicKeyOut(clientKey!!.skpi, jwk)
    }

    private suspend fun loadClientKeysFromStorage(): KeyPair? {
        val privRaw = storage.getClientPrivateKey()
        val pubRaw = storage.getClientPublicKey()

        if (privRaw == null || pubRaw == null) return null

        val privPem = decodeHexPem(privRaw)
        val pubPem = decodeHexPem(pubRaw)

        return try {
            keyPairGenerator.loadKeys(
                decodePem(privPem),
                decodePem(pubPem),
            )
        } catch (ex: Exception) {
            Log.d { "Failed to load keys: ${ex.message}" }
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

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun generateDpopKey(): PublicKeyOut {
        if (dpopKey == null) {
            dpopKey = keyPairGenerator.generateKeys()
            storage.saveDpopKeys(
                toPem("PUBLIC KEY", dpopKey!!.skpi),
                toPem("PRIVATE KEY", dpopKey!!.privateKey),
            )
        }
        val jwk = keyPairGenerator.toJwk(dpopKey!!.skpi)

        return PublicKeyOut(dpopKey!!.skpi, jwk)
    }

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun signWithClientKey(input: ByteArray): ByteArray {
        checkNotNull(clientKey) { "Client key not initialized" }
        return signForJws(clientKey!!.privateKey, input)
    }

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun signWithDpopKey(input: ByteArray): ByteArray {
        checkNotNull(dpopKey) { "DPoP key not initialized" }
        return signForJws(dpopKey!!.privateKey, input)
    }

    override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray {
        check(p12File.isNotEmpty()) { "SM-B certificate .PEM file is empty" }
        return x509PemReader.loadCertificate(p12File, alias, password)
    }

    override suspend fun getRegistrationNumber(certificate: ByteArray): String {
        return x509PemReader.getRegistrationNumber(certificate).orEmpty()
    }

    override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray {
        val smbKey = x509PemReader.loadPrivateKey(p12File, alias, password)
        return signForJws(smbKey, input)
    }

    override suspend fun randomUuid(): Uuid = Uuid.random()

    override fun forget() {
        clientKey = null
        dpopKey = null
    }

    fun signForJws(privateKey: ByteArray, signingInput: ByteArray): ByteArray {
        val s = signer.sign(privateKey, signingInput)
        return derEcdsaToJose(s, 32)
    }
}

@Suppress("FunctionOnlyReturningConstant")
internal fun hardwareBackedAvailable(): Boolean = false
internal actual fun platformDefaultProvider(storage: TpmStorage): TpmProvider {
    if (hardwareBackedAvailable()) {
        Log.d { "Using hardware crypto provider (JVM)" }
        TODO("hardware backed provider")
    } else {
        Log.d { "Using software crypto provider (JVM)" }
        return SoftwareCryptoProvider(storage, EcdhP256Kem(), EcdhSigner(), X509PemReader())
    }
}
