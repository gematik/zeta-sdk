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

import AsymAlg
import Jwk
import PublicKeyOut
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.KeyPair
import de.gematik.zeta.sdk.crypto.X509PemReader
import de.gematik.zeta.sdk.crypto.hashWithSha256
import derEcdsaToJose
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.EC.PrivateKey
import dev.whyoleg.cryptography.algorithms.EC.PublicKey
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.util.encodeBase64
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

private class SoftwareCryptoProvider(
    private val storage: TpmStorage,
    private val x509PemReader: X509PemReader,
) : TpmProvider {
    override val isHardwareBacked: Boolean = false

    private var clientKey: KeyPair? = null
    private var dpopKey: KeyPair? = null

    private val provider = CryptographyProvider.Default
    private val ec = provider.get(ECDSA)

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun generateClientInstanceKey(): PublicKeyOut {
        if (clientKey == null) {
            clientKey = loadClientKeysFromStorage()
            if (clientKey == null) {
                val kp = ec.keyPairGenerator(EC.Curve.P256).generateKey()
                val pubBytes = kp.publicKey.encodeToByteArray(PublicKey.Format.RAW)
                val privBytes = kp.privateKey.encodeToByteArray(PrivateKey.Format.RAW)

                clientKey = KeyPair(
                    skpi = pubBytes,
                    sec1 = byteArrayOf(),
//                    sec1 = kp.publicKey.encodeToByteArray(PublicKey.Format.RAW),
                    privateKey = privBytes,
                )

                storage.saveClientKeys(
                    toPem("PUBLIC KEY", clientKey!!.skpi),
                    toPem("PRIVATE KEY", clientKey!!.privateKey),
                )
            }
        }

        val jwk = toJwk(clientKey!!.skpi)
        return PublicKeyOut(clientKey!!.skpi, jwk)
    }

    @Suppress("UnsafeCallOnNullableType")
    override suspend fun generateDpopKey(): PublicKeyOut {
        if (dpopKey == null) {
            val kp = ec.keyPairGenerator(EC.Curve.P256).generateKey()
            val pubBytes = kp.publicKey.encodeToByteArray(PublicKey.Format.RAW)
            val privBytes = kp.privateKey.encodeToByteArray(PrivateKey.Format.RAW)

            dpopKey = KeyPair(
                skpi = pubBytes,
                sec1 = byteArrayOf(),
//                sec1 = kp.publicKey.encodeToByteArray(PublicKey.Format.RAW),
                privateKey = privBytes,
            )

            storage.saveDpopKeys(
                toPem("PUBLIC KEY", dpopKey!!.skpi),
                toPem("PRIVATE KEY", dpopKey!!.privateKey),
            )
        }
        val jwk = toJwk(dpopKey!!.skpi)
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
        return signForJwsBp(smbKey, input)
    }

    override suspend fun randomUuid(): Uuid = Uuid.random()

    override fun forget() {
        clientKey = null
        dpopKey = null
    }

    private suspend fun signForJws(privateKey: ByteArray, signingInput: ByteArray): ByteArray {
        val priv = ec.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(PrivateKey.Format.RAW, privateKey)

        val sig = priv.signatureGenerator(SHA256, ECDSA.SignatureFormat.DER)
            .generateSignature(signingInput)
        return derEcdsaToJose(sig, 32)
    }

    private suspend fun signForJwsBp(privateKey: ByteArray, signingInput: ByteArray): ByteArray {
        val priv = ec.privateKeyDecoder(EC.Curve.brainpoolP256r1)
            .decodeFromByteArray(PrivateKey.Format.DER, privateKey)

        val sig = priv.signatureGenerator(SHA256, ECDSA.SignatureFormat.DER)
            .generateSignature(signingInput)
        return derEcdsaToJose(sig, 32)
    }

    private fun toPem(type: String, der: ByteArray): String {
        val b64 = Base64.Pem.encode(der)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n"
    }

    private suspend fun loadClientKeysFromStorage(): KeyPair? {
        val privRaw = storage.getClientPrivateKey() ?: return null
        val pubRaw = storage.getClientPublicKey() ?: return null

        val privPem = decodeHexPem(privRaw)
        val pubPem = decodeHexPem(pubRaw)

        return try {
            val privBytes = decodePem(privPem)
            val pubBytes = decodePem(pubPem)

            KeyPair(
                skpi = pubBytes,
                sec1 = byteArrayOf(),
//                sec1 = ec.publicKeyDecoder(EC.Curve.P256)
//                    .decodeFromByteArray(PublicKey.Format.DER, pubBytes)
//                    .encodeToByteArray(PublicKey.Format.DER),
                privateKey = privBytes,
            )
        } catch (ex: Exception) {
            Log.d { "Failed to load keys: ${ex.message}" }
            null
        }
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
        val provider = CryptographyProvider.Default
        val ec = provider.get(ECDSA)

        // Decode the public key from DER or SEC1 format (use what you stored)
        val pub: PublicKey = ec.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(PublicKey.Format.RAW.Uncompressed, publicKey)

        // Extract raw SEC1 uncompressed point bytes
        val sec1: ByteArray = pub.encodeToByteArray(PublicKey.Format.RAW.Uncompressed)

        require(sec1.size == 65 && sec1[0] == 0x04.toByte()) { "Invalid P-256 public key" }

        val x = sec1.copyOfRange(1, 33)
        val y = sec1.copyOfRange(33, 65)

        val xB = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(x)
        val yB = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(y)

        // Compute the JWK kid from SHA-256 hash of {"crv":"P-256","kty":"EC","x":"xB","y":"yB"}
        val jwkJson = """{"crv":"P-256","kty":"EC","x":"$xB","y":"$yB"}"""
        val kid = hashWithSha256(jwkJson.encodeToByteArray()).encodeBase64() // urlSafe = true

        return Jwk(
            kid = kid,
            kty = "EC",
            alg = AsymAlg.ES256.name,
            use = "sig",
            crv = "P-256",
            x = xB,
            y = yB,
        )
    }
}

internal actual fun platformDefaultProvider(storage: TpmStorage): TpmProvider = SoftwareCryptoProvider(storage, X509PemReader())
