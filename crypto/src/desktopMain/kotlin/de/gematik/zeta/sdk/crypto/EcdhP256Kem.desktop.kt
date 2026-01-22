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

package de.gematik.zeta.sdk.crypto

import AsymAlg
import Jwk
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.EC.Curve.Companion.P256
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64

actual class EcdhP256Kem actual constructor() : Kem {

    private val provider = CryptographyProvider.Default
    private val ecdh = provider.get(ECDH)
    private val ecdsa = provider.get(ECDSA)

    actual override fun generateKeys(): KeyPair {
        val eph = ecdh.keyPairGenerator(P256).generateKeyBlocking()
        return KeyPair(
            skpi = eph.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW),
            sec1 = eph.publicKey.toSec1Uncompressed(),
            privateKey = eph.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.RAW),
        )
    }

    actual override fun encapsulate(peerPublicKey: ByteArray): KemEncapResult {
        val peer = ecdh.publicKeyDecoder(P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, peerPublicKey)
        val eph = ecdh.keyPairGenerator(P256).generateKeyBlocking()
        val ss = peer.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(eph.privateKey)
        val ct = eph.publicKey.toSec1Uncompressed()

        return KemEncapResult(ciphertext = ct, sharedSecret = ss)
    }

    actual override fun decapsulate(privateKeyRaw: ByteArray, ciphertext: ByteArray): ByteArray {
        val ephPub = ecdh.publicKeyDecoder(P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, ciphertext)
        val priv = ecdh.privateKeyDecoder(P256)
            .decodeFromByteArrayBlocking(EC.PrivateKey.Format.RAW, privateKeyRaw)

        return priv.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(ephPub)
    }

    actual fun toJwk(publicKey: ByteArray): Jwk {
        val pub: EC.PublicKey = ecdsa.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed, publicKey)

        val sec1: ByteArray = pub.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed)

        require(sec1.size == 65 && sec1[0] == 0x04.toByte()) { "Invalid P-256 public key" }

        val x = sec1.copyOfRange(1, 33)
        val y = sec1.copyOfRange(33, 65)

        val xB = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(x)
        val yB = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(y)

        val jwkJson = """{"crv":"P-256","kty":"EC","x":"$xB","y":"$yB"}"""
        val kid = Base64.UrlSafe.encode(hashWithSha256(jwkJson.encodeToByteArray()))

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

    actual fun loadKeys(priv: ByteArray, pub: ByteArray): KeyPair {
        val privateKey = ecdh.privateKeyDecoder(P256)
            .decodeFromByteArrayBlocking(EC.PrivateKey.Format.RAW, priv)
        val publicKey = ecdh.publicKeyDecoder(P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, pub)
        return KeyPair(
            skpi = publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW),
            sec1 = publicKey.toSec1Uncompressed(),
            privateKey = privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.RAW),
        )
    }
}

fun ECDH.PublicKey.toSec1Uncompressed(): ByteArray {
    val sec1 = encodeToByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed)
    require(sec1.size == 65 && sec1[0] == 0x04.toByte()) { "Invalid P-256 public key" }
    val x = sec1.copyOfRange(1, 33)
    val y = sec1.copyOfRange(33, 65)
    return byteArrayOf(0x04) + x + y
}

actual fun hashWithSha256(input: ByteArray): ByteArray =
    CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(input)
