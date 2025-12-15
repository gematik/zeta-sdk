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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.bytesToLongBigEndian
import de.gematik.zeta.longToBytesBigEndian
import de.gematik.zeta.sliceExact

internal const val VERSION_OFFSET = 0
internal const val PU_OFFSET = 1
internal const val KIND_OFFSET = 2 // 1=request, 2=response
internal const val CTR_OFFSET = 3 // 8 bytes, big-endian
internal const val KEYID_OFFSET = 11 // 32 bytes
internal const val HEADER_LEN = 43 // 1 + 1 + 1 + 8 + 32

public enum class Environment {
    Production,
    Testing,
}

public data class ZetaHeader(
    val version: Byte = 0x02,
    val pu: Environment,
    val kind: Kind,
    val counter: Long,
    val keyId: ByteArray, // 32 bytes
) {
    public fun toBytes(): ByteArray {
        require(keyId.size == 32) { "KeyId must be $32 bytes, got ${keyId.size}" }

        val out = ByteArray(HEADER_LEN)
        out[VERSION_OFFSET] = version
        out[PU_OFFSET] = when (pu) {
            Environment.Production -> 0.toByte()
            Environment.Testing -> 1.toByte()
        }
        out[KIND_OFFSET] = kind.v

        val ctr = longToBytesBigEndian(counter)
        ctr.copyInto(out, destinationOffset = CTR_OFFSET, startIndex = 0, endIndex = 8)
        keyId.copyInto(out, destinationOffset = KEYID_OFFSET, startIndex = 0, endIndex = 32)

        return out
    }

    public companion object {
        public fun from(extended: ByteArray): ZetaHeader {
            require(extended.size >= HEADER_LEN) { "Extended data too short for header" }

            val version = extended[VERSION_OFFSET]
            require(version == 0x02.toByte()) { "Invalid version: $version" }

            val pu = when (extended[PU_OFFSET]) {
                0.toByte() -> Environment.Production
                1.toByte() -> Environment.Testing
                else -> error("Invalid PU byte: ${extended[PU_OFFSET]}")
            }
            val kind = when (extended[KIND_OFFSET]) {
                1.toByte() -> Kind.Request
                2.toByte() -> Kind.Response
                else -> error("Invalid kind byte: ${extended[KIND_OFFSET]}")
            }
            val counter = bytesToLongBigEndian(extended, CTR_OFFSET)
            val keyId = sliceExact(extended, KEYID_OFFSET, KEYID_OFFSET + 32)

            return ZetaHeader(version, pu, kind, counter, keyId)
        }
    }
}
