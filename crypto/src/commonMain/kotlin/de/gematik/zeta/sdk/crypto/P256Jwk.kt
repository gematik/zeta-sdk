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
import kotlin.io.encoding.Base64

/**
 * Builds the ES256 signing [Jwk] from the affine coordinates of a P-256 public key.
 *
 * [x] and [y] must be the 32-byte big-endian unsigned coordinates. The `kid` is the
 * base64url(SHA-256) of the canonical JWK JSON.
 */
fun p256CoordinatesToJwk(x: ByteArray, y: ByteArray): Jwk {
    val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val xEncoded = encoder.encode(x)
    val yEncoded = encoder.encode(y)
    val jwkJson = """{"crv":"P-256","kty":"EC","x":"$xEncoded","y":"$yEncoded"}"""
    val kid = encoder.encode(hashWithSha256(jwkJson.encodeToByteArray()))

    return Jwk(
        kid = kid,
        kty = "EC",
        alg = AsymAlg.ES256.name,
        use = "sig",
        crv = "P-256",
        x = xEncoded,
        y = yEncoded,
    )
}

/**
 * Builds the ES256 signing [Jwk] for a P-256 public key given as its uncompressed 65-byte point
 * (`0x04 || X || Y`).
 */
fun p256UncompressedPointToJwk(point: ByteArray): Jwk {
    require(point.size == 65 && point[0] == 0x04.toByte()) { "Invalid P-256 public key point" }
    return p256CoordinatesToJwk(point.copyOfRange(1, 33), point.copyOfRange(33, 65))
}

/**
 * SHA-256 of a P-256 public key in uncompressed form (`0x04 || X || Y`).
 */
fun sha256PublicKeyPoint(point: ByteArray): ByteArray {
    require(point.size == 65 && point[0] == 0x04.toByte()) { "Invalid P-256 public key point" }
    return hashWithSha256(point)
}
