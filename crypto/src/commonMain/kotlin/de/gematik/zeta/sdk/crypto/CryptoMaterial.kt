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

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

data class PublicKeyOut(val encoded: ByteArray, val jwk: Jwk)

// RFC 4122 UUID v4
fun randomUUID(): String = Uuid.random().toString()

fun derEcdsaToJose(der: ByteArray, size: Int = 32): ByteArray {
    require(der.isNotEmpty() && der[0] == 0x30.toByte()) { "Not a DER SEQUENCE" }

    var i = 2
    fun readInt(): ByteArray {
        require(der[i] == 0x02.toByte()) { "Not INTEGER" }
        val len = der[i + 1].toInt() and 0xFF
        val start = i + 2
        val end = start + len
        i = end
        var v = der.copyOfRange(start, end)

        while (v.size > 1 && v[0] == 0.toByte()) v = v.copyOfRange(1, v.size)
        return v
    }
    val r = readInt()
    val s = readInt()

    fun leftPadTo(x: ByteArray, n: Int): ByteArray =
        if (x.size < n) ByteArray(n - x.size) + x else x.takeLast(n).toByteArray()

    return leftPadTo(r, size) + leftPadTo(s, size) // 32 + 32 = 64 bytes
}

fun joseToDerEcdsa(jose: ByteArray): ByteArray {
    require(jose.size % 2 == 0) { "JOSE signature length must be even" }

    val size = jose.size / 2
    val r = jose.copyOfRange(0, size)
    val s = jose.copyOfRange(size, jose.size)

    fun encodeInt(x: ByteArray): ByteArray {
        var v = x.dropWhile { it == 0.toByte() }.toByteArray()
        if (v.isEmpty()) v = byteArrayOf(0)
        // If the highest bit is set, prepend 0x00 to indicate positive INTEGER
        if (v[0].toInt() and 0x80 != 0) {
            v = byteArrayOf(0) + v
        }
        return byteArrayOf(0x02) + byteArrayOf(v.size.toByte()) + v
    }

    val derR = encodeInt(r)
    val derS = encodeInt(s)
    val sequence = derR + derS

    return byteArrayOf(0x30) + byteArrayOf(sequence.size.toByte()) + sequence
}

@Serializable
data class Jwk(
    val kid: String,
    val kty: String,
    val alg: String,
    val use: String,
    val crv: String,
    val x: String,
    val y: String,
)

enum class AsymAlg { ES256 }

fun secureRandom(b: ByteArray): ByteArray {
    return CryptographyRandom.nextBytes(b)
}
