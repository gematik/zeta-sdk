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

package de.gematik.zeta

public fun longToBytesBigEndian(x: Long): ByteArray {
    val out = ByteArray(8)
    for (i in 0 until 8) {
        out[i] = ((x ushr (56 - 8 * i)) and 0xFF).toByte()
    }
    return out
}

public fun bytesToLongBigEndian(b: ByteArray, off: Int = 0): Long {
    require(b.size >= off + 8) { "Need 8 bytes for Long at offset $off" }
    var v = 0L
    for (i in 0 until 8) {
        v = (v shl 8) or (b[off + i].toLong() and 0xFF)
    }
    return v
}

public fun sliceExact(src: ByteArray, from: Int, toExclusive: Int): ByteArray {
    require(from >= 0 && toExclusive >= from && toExclusive <= src.size) {
        "slice out of bounds: from=$from, to=$toExclusive, size=${src.size}"
    }
    val out = ByteArray(toExclusive - from)
    src.copyInto(out, destinationOffset = 0, startIndex = from, endIndex = toExclusive)
    return out
}

public fun Long.toBigEndian8(): ByteArray =
    byteArrayOf(
        ((this ushr 56) and 0xFF).toByte(),
        ((this ushr 48) and 0xFF).toByte(),
        ((this ushr 40) and 0xFF).toByte(),
        ((this ushr 32) and 0xFF).toByte(),
        ((this ushr 24) and 0xFF).toByte(),
        ((this ushr 16) and 0xFF).toByte(),
        ((this ushr 8) and 0xFF).toByte(),
        (this and 0xFF).toByte(),
    )

public fun concat(vararg parts: ByteArray): ByteArray {
    val total = parts.sumOf { it.size }
    val out = ByteArray(total)
    var p = 0
    for (buf in parts) {
        buf.copyInto(out, p)
        p += buf.size
    }

    return out
}
