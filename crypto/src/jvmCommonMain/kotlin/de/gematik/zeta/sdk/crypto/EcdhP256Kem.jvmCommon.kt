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

import Jwk
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

actual class EcdhP256Kem actual constructor() : Kem {
    actual override fun generateKeys(): KeyPair {
        val kp = genEphemeral()

        val pk = kp.public as ECPublicKey
        // SEC1 uncompressed: 0x04 || X(32) || Y(32) -> 65 bytes for P-256
        val sec1 = getSec1(pk)

        return KeyPair(kp.public.encoded, sec1, kp.private.encoded)
    }

    actual override fun encapsulate(peerPublicKey: ByteArray): KemEncapResult {
        val peer = publicFromUncompressed(peerPublicKey)
        val eph = genEphemeral()
        val ss = agree(eph.private, peer)
        val ct = getSec1(eph.public as ECPublicKey)

        return KemEncapResult(ct, ss)
    }

    actual override fun decapsulate(privateKeyRaw: ByteArray, ciphertext: ByteArray): ByteArray {
        val ephPub = publicFromUncompressed(ciphertext)

        return agree(privateFromPkcs8(privateKeyRaw), ephPub)
    }

    private fun genEphemeral(): java.security.KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    // SEC1 uncompressed: 0x04 || X(32) || Y(32)   => 65 bytes for P-256
    private fun getSec1(pk: ECPublicKey): ByteArray {
        val x = pk.w.affineX.let { toFixedUnsigned(it) }
        val y = pk.w.affineY.let { toFixedUnsigned(it) }
        return byteArrayOf(0x04) + x + y
    }

    actual fun toJwk(publicKey: ByteArray): Jwk {
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(X509EncodedKeySpec(publicKey)) as ECPublicKey

        require(pub.params.curve.field.fieldSize == 256) { "Expected P-256" }
        val x = toFixedUnsigned(pub.w.affineX)
        val y = toFixedUnsigned(pub.w.affineY)
        val xB = b64url(x); val yB = b64url(y)
        val kid = b64url(hashWithSha256("""{"crv":"P-256","kty":"EC","x":"$xB","y":"$yB"}""".toByteArray()))

        return Jwk(kid = kid, kty = "EC", alg = AsymAlg.ES256.name, use = "sig", crv = "P-256", x = xB, y = yB)
    }

    actual fun loadKeys(priv: ByteArray, pub: ByteArray): KeyPair {
        val kf = KeyFactory.getInstance("EC")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(priv))
        val pubKey = kf.generatePublic(X509EncodedKeySpec(pub))

        return toKeyPair(privKey, pubKey)
    }

    private fun toKeyPair(priv: PrivateKey, pub: PublicKey): KeyPair {
        val pk = pub as ECPublicKey

        val sec1 = getSec1(pk)
        return KeyPair(pub.encoded, sec1, priv.encoded)
    }

    private fun toFixedUnsigned(bi: BigInteger): ByteArray {
        val size = 32
        var raw = bi.toByteArray()
        if (raw.size > 1 && raw[0] == 0.toByte()) raw = raw.copyOfRange(1, raw.size)
        return when {
            raw.size == size -> raw
            raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
            else -> ByteArray(size - raw.size) + raw
        }
    }

    private fun b64url(b: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun ecParams(): ECParameterSpec =
        AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)

    private fun publicFromUncompressed(uncompressed: ByteArray): PublicKey {
        require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) { "Invalid uncompressed EC key" }
        val x = uncompressed.copyOfRange(1, 33)
        val y = uncompressed.copyOfRange(33, 65)
        val spec = ECPublicKeySpec(
            ECPoint(BigInteger(1, x), BigInteger(1, y)),
            ecParams(),
        )
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun privateFromPkcs8(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    private fun agree(priv: PrivateKey, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(priv); doPhase(peer, true); generateSecret()
        }
}

actual fun hashWithSha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)
