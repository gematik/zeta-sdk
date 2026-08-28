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

import Jwk
import PublicKeyOut
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.KeyPair
import de.gematik.zeta.sdk.crypto.X509PemReader
import de.gematik.zeta.sdk.crypto.p256UncompressedPointToJwk
import derEcdsaToJose
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.EC.PrivateKey
import dev.whyoleg.cryptography.algorithms.EC.PublicKey
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

/**
 * Software [TpmProvider] implementation shared by every Kotlin/Native target.
 *
 * It relies on the multiplatform `dev.whyoleg.cryptography` provider for P-256 key
 * generation, JWS signing and JWK export, and stores keys as hex-encoded PEM through
 * [TpmStorage]. The only piece left to the concrete platform provider is SM-B signing
 * ([signSmb]), which uses a brainpool P-256r1 key and differs per platform.
 */
internal abstract class NativeSoftwareCryptoProvider(
    storage: TpmStorage,
    x509PemReader: X509PemReader,
) : AbstractSoftwareCryptoProvider(storage, x509PemReader) {
    private val provider = CryptographyProvider.Default

    // CryptographyProvider.get() cannot be replaced with index operator
    // as the [] operator is not available on this type
    protected val ec = provider.get(ECDSA)

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut {
        if (clientKey == null) {
            clientKey = loadClientKeysFromStorage()
            if (clientKey == null) {
                clientKey = generateP256KeyPair()

                storage.saveClientKeys(
                    toPem("PUBLIC KEY", clientKey!!.skpi),
                    toPem("PRIVATE KEY", clientKey!!.privateKey),
                )
            }
        }

        val jwk = toJwk(clientKey!!.skpi)
        return PublicKeyOut(clientKey!!.skpi, jwk)
    }

    override suspend fun generateDpopKey(): PublicKeyOut {
        val existing = loadDpopKeysFromStorage()
        val key = if (existing != null) {
            existing
        } else {
            val generated = generateP256KeyPair()

            storage.saveDpopKeys(
                toPem("PUBLIC KEY", generated.skpi),
                toPem("PRIVATE KEY", generated.privateKey),
            )

            generated
        }

        return PublicKeyOut(key.skpi, toJwk(key.skpi))
    }

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun signWithClientKey(input: ByteArray): ByteArray {
        checkNotNull(clientKey) { "Client key not initialized" }
        return signForJws(clientKey!!.privateKey, input)
    }

    override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray {
        val key = checkNotNull(loadDpopKeysFromStorage()) {
            "DPoP key not found for resource: $resource"
        }
        return signForJws(key.privateKey, input)
    }

    private suspend fun loadDpopKeysFromStorage(): KeyPair? =
        loadKeyPairFromStorage(
            privRaw = storage.getDpopPrivateKey(),
            pubRaw = storage.getDpopPublicKey(),
            keyName = "DPoP",
        )

    private suspend fun loadClientKeysFromStorage(): KeyPair? =
        loadKeyPairFromStorage(
            privRaw = storage.getClientPrivateKey(),
            pubRaw = storage.getClientPublicKey(),
            keyName = "client",
        )

    private fun loadKeyPairFromStorage(privRaw: String?, pubRaw: String?, keyName: String): KeyPair? {
        if (privRaw == null || pubRaw == null) return null

        return try {
            KeyPair(
                skpi = decodePem(decodeHexPem(pubRaw)),
                sec1 = byteArrayOf(),
                privateKey = decodePem(decodeHexPem(privRaw)),
            )
        } catch (ex: Exception) {
            Log.d { "Failed to load $keyName keys: ${ex.message}" }
            null
        }
    }

    private suspend fun generateP256KeyPair(): KeyPair {
        val kp = ec.keyPairGenerator(EC.Curve.P256).generateKey()
        return KeyPair(
            skpi = kp.publicKey.encodeToByteArray(PublicKey.Format.RAW),
            sec1 = byteArrayOf(),
            privateKey = kp.privateKey.encodeToByteArray(PrivateKey.Format.RAW),
        )
    }

    private suspend fun signForJws(privateKey: ByteArray, signingInput: ByteArray): ByteArray {
        val priv = ec.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(PrivateKey.Format.RAW, privateKey)
        val sig = priv.signatureGenerator(SHA256, ECDSA.SignatureFormat.DER)
            .generateSignature(signingInput)
        return derEcdsaToJose(sig, 32)
    }

    private fun toPem(type: String, der: ByteArray): String {
        val b64 = Base64.Pem.encode(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    private fun decodePem(pem: String): ByteArray {
        val b64 = pem.lineSequence().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        return Base64.Pem.decode(b64)
    }

    private fun decodeHexPem(s: String): String {
        if (s.length % 2 != 0 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return s
        val bytes = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val txt = bytes.decodeToString()
        return if (txt.startsWith("-----BEGIN ")) txt else s
    }

    private suspend fun toJwk(publicKey: ByteArray): Jwk {
        val pub: PublicKey = ec.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(PublicKey.Format.RAW.Uncompressed, publicKey)
        val sec1: ByteArray = pub.encodeToByteArray(PublicKey.Format.RAW.Uncompressed)
        return p256UncompressedPointToJwk(sec1)
    }
}
