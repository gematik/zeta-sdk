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

import de.gematik.zeta.sdk.WsClientExtension.WsSession.WsHandler
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking

public object WsClientExtension {

    @JvmStatic
    public fun ws(
        sdk: ZetaSdkClient,
        targetUrl: String,
        builder: ZetaHttpClientBuilder.() -> Unit = {},
        customHeaders: Map<String, String>,
        handler: WsHandler,
    ) {
        runBlocking {
            sdk.ws(targetUrl, builder, customHeaders = customHeaders) {
                val session = WsSession(this)
                handler.handle(session)
            }
        }
    }

    public sealed class WsMessage {
        public data class Text(val text: String) : WsMessage()
        public data class Binary(val bytes: ByteArray) : WsMessage()
        public object Close : WsMessage()
    }

    public class WsSession(
        private val session: DefaultClientWebSocketSession,
    ) {
        public fun sendText(text: String) {
            runBlocking {
                session.send(Frame.Text(text))
            }
        }

        public fun sendBinary(data: ByteArray) {
            runBlocking {
                session.send(Frame.Binary(true, data))
            }
        }

        public fun receiveNext(): WsMessage? = runBlocking {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        return@runBlocking WsMessage.Text(text)
                    }

                    is Frame.Binary -> {
                        val bytes = frame.readBytes()
                        return@runBlocking WsMessage.Binary(bytes)
                    }

                    is Frame.Close -> {
                        return@runBlocking WsMessage.Close
                    }

                    else -> {
                        // ignore other Frames
                    }
                }
            }
            null
        }

        public fun close() {
            runBlocking {
                session.close()
            }
        }

        public fun interface WsHandler {
            public fun handle(session: WsSession)
        }
    }
}

private const val NULL_CHAR = '\u0000'

public fun stompConnectFrame(host: String): String =
    buildString {
        append("CONNECT\n")
        append("accept-version:1.2\n")
        append("host:$host\n")
        append("\n")
        append(NULL_CHAR)
    }

public fun stompSubscribeFrame(id: String, destination: String): String =
    buildString {
        append("SUBSCRIBE\n")
        append("id:").append(id).append('\n')
        append("destination:").append(destination).append('\n')
        append("\n")
        append(NULL_CHAR)
    }

public fun stompSendFrame(destination: String, bodyJson: String): String =
    buildString {
        append("SEND\n")
        append("destination:").append(destination).append('\n')
        append("content-type:application/json\n")
        append("\n")
        append(bodyJson)
        append(NULL_CHAR)
    }
