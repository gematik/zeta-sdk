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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val PADDED_SIZE = 1024
private const val HEADER_LEN = 6

private fun pad(message: String): ByteArray {
    val messageBytes = message.encodeToByteArray()
    val padLength = PADDED_SIZE - HEADER_LEN - messageBytes.size
    return "PNM1".encodeToByteArray() +
        byteArrayOf((padLength shr 8).toByte(), (padLength and 0xFF).toByte()) +
        ByteArray(padLength) { ' '.code.toByte() } +
        messageBytes
}

class PushMessagePaddingTest {

    /** `A_27610`'s own example: message "test" pads to hexdump `504e4d31 03f6 0202...0202 74657374`. */
    @Test
    fun unpadPushMessage_matchesSpecExample() {
        val padded = pad("test")

        assertEquals(1024, padded.size)
        assertEquals("test", unpadPushMessage(padded))
    }

    @Test
    fun unpadPushMessage_handlesEmptyMessage() {
        assertEquals("", unpadPushMessage(pad("")))
    }

    @Test
    fun unpadPushMessage_handlesMessageFillingTheWholeBudget() {
        val message = "x".repeat(PADDED_SIZE - HEADER_LEN)
        assertEquals(message, unpadPushMessage(pad(message)))
    }

    @Test
    fun unpadPushMessage_throwsWhenNotExactly1024Bytes() {
        assertFailsWith<IllegalArgumentException> { unpadPushMessage(ByteArray(100)) }
    }

    @Test
    fun unpadPushMessage_throwsWhenMagicIsWrong() {
        assertFailsWith<IllegalArgumentException> { unpadPushMessage(ByteArray(PADDED_SIZE)) }
    }

    @Test
    fun unpadPushMessage_throwsWhenPaddingLengthIsInvalid() {
        val corrupted = "PNM1".encodeToByteArray() +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()) +
            ByteArray(PADDED_SIZE - HEADER_LEN)

        assertFailsWith<IllegalArgumentException> { unpadPushMessage(corrupted) }
    }
}
