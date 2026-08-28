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

package de.gematik.zeta.sdk

import io.ktor.utils.io.core.toByteArray
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class WsReceiveAssembledFrameTest {

    @Test
    fun receiveAssembledFrame_reassembles_text_split_across_multiple_frames() = runTest {
        val incoming = Channel<Frame>(capacity = Channel.UNLIMITED)

        incoming.send(Frame.Text(fin = false, data = "{\"version\":\"1.0.0\",".toByteArray()))
        incoming.send(Frame.Text(fin = true, data = "\"type\":\"ConnectorScenario\"}".toByteArray()))
        incoming.close()

        val result = receiveAssembledFrame(incoming)

        assertTrue(result is AssembledFrame.Text)
        assertEquals(
            "{\"version\":\"1.0.0\",\"type\":\"ConnectorScenario\"}",
            result.text,
        )
    }

    @Test
    fun receiveAssembledFrame_returns_single_frame_when_fin_is_true_immediately() = runTest {
        val incoming = Channel<Frame>(capacity = Channel.UNLIMITED)
        incoming.send(Frame.Text(fin = true, data = "{\"ok\":true}".toByteArray()))
        incoming.close()

        val result = receiveAssembledFrame(incoming)

        assertTrue(result is AssembledFrame.Text)
        assertEquals("{\"ok\":true}", result.text)
    }

    @Test
    fun receiveAssembledFrame_reassembles_binary_split_across_multiple_frames() = runTest {
        val incoming = Channel<Frame>(capacity = Channel.UNLIMITED)
        incoming.send(Frame.Binary(fin = false, data = byteArrayOf(0x01, 0x02)))
        incoming.send(Frame.Binary(fin = true, data = byteArrayOf(0x03, 0x04)))
        incoming.close()

        val result = receiveAssembledFrame(incoming)

        assertTrue(result is AssembledFrame.Binary)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), result.bytes)
    }
}
