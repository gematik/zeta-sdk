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

import de.gematik.zeta.concat
import de.gematik.zeta.sdk.crypto.AesGcmCipher
import de.gematik.zeta.sdk.crypto.unpackAead
import de.gematik.zeta.toBigEndian8
import kotlinx.serialization.Serializable
import secureRandom

internal const val IV_LEN = 12 // GCM nonce length
internal const val MIN_EXT_LEN = 72 // Spec says at least 72 bytes
public enum class Kind(public val v: Byte) { Request(1), Response(2) }

/**
 * Encrypt an inner HTTP request (raw bytes) into the extended ciphertext.
 * */
@Serializable
public data class EstablishedSession(
    val keyId: ByteArray,
    val c2sAppDataKey: ByteArray,
    val s2cAppDataKey: ByteArray,
    val cid: String? = null,
    val pu: Environment = Environment.Production,
    // A_26927: increment per request
    var requestCounter: Long = 0L,
    // A_26926: increments per encryption, first used = 1
    var encCounter: Long = 0L,
) {
    init {
        require(keyId.size == 32) { "KeyID must be 32 bytes" }
        require(c2sAppDataKey.size == 32 && s2cAppDataKey.size == 32) { "K2 app data keys must be 32 bytes" }
    }

    public fun encryptRequest(plainText: ByteArray): ByteArray {
        requestCounter += 1

        val header = buildHeader(requestCounter)
        val iv = buildIv()

        val cipher = AesGcmCipher()
        val blob = cipher.encrypt(c2sAppDataKey, plainText, iv, header)
        val aeadParts = blob.unpackAead()

        val packed = concat(header, aeadParts.iv, aeadParts.cipherText, aeadParts.tag)

        // A_26928 minimum length: 43 + 12 + 16 + ≥1 = 72
        require(packed.size >= 72) { "Ciphertext too short (must be >= 72 bytes as per A_26928)" }

        return packed
    }

    public fun decryptResponse(extended: ByteArray): ByteArray {
        require(extended.size >= MIN_EXT_LEN) { "Extended ciphertext too short (min $MIN_EXT_LEN bytes)." }
        val header = ZetaHeader.from(extended)
        require(header.pu == this.pu) { "PU/nonPU mismatch." }
        require(header.kind == Kind.Response) { "Expected kind=2 (response)." }

        require(header.counter == requestCounter) {
            "Response counter ${header.counter} != expected $requestCounter"
        }

        require(header.keyId.contentEquals(this.keyId)) { "Unknown or mismatched KeyID." }

        val ivStart = HEADER_LEN
        val ivEnd = ivStart + IV_LEN
        require(extended.size > ivEnd) { "No ciphertext after IV." }

        val iv = extended.copyOfRange(ivStart, ivEnd)
        val ct = extended.copyOfRange(ivEnd, extended.size)

        val aad = header.toBytes()

        return AesGcmCipher().decrypt(s2cAppDataKey, ct, iv, aad)
    }

    private fun buildHeader(nextReqCtr: Long): ByteArray = ZetaHeader(
        pu = pu,
        keyId = keyId,
        counter = nextReqCtr,
        kind = Kind.Request,
    ).toBytes()

    private fun buildIv(): ByteArray {
        // A_26926: first used value must be 1
        encCounter += 1
        val a = ByteArray(4).also { secureRandom(it) }
        val ctr = encCounter.toBigEndian8()

        // iv length = 12
        return a + ctr
    }
}
