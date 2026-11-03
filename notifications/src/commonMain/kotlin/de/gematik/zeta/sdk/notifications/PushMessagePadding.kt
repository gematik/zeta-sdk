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

package de.gematik.zeta.sdk.notifications

private const val PADDED_SIZE = 1024
private const val MAGIC = "PNM1"
private const val HEADER_LEN = 6 // 4-byte magic + 2-byte big-endian padding length

/**
 * Reverses the fixed-size padding the Fachdienst applies before encrypting (`A_27610`):
 * `"PNM1" + 2-byte big-endian pad length + that many 0x20 bytes + the message`, concatenated to
 * exactly [PADDED_SIZE] bytes so ciphertext size never leaks the message length.
 */
internal fun unpadPushMessage(padded: ByteArray): String {
    require(padded.size == PADDED_SIZE) {
        "Decrypted push message must be exactly $PADDED_SIZE bytes, got ${padded.size}"
    }
    val magic = padded.copyOfRange(0, MAGIC.length).decodeToString()
    require(magic == MAGIC) { "Unexpected push message header '$magic'" }

    val padLength = ((padded[4].toInt() and 0xFF) shl 8) or (padded[5].toInt() and 0xFF)
    val messageStart = HEADER_LEN + padLength
    require(messageStart in HEADER_LEN..PADDED_SIZE) { "Invalid push message padding length $padLength" }

    return padded.copyOfRange(messageStart, PADDED_SIZE).decodeToString()
}
