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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

object WsClientAsyncExtension {
    @OptIn(DelicateCoroutinesApi::class)
    @JvmStatic
    fun wsAsync(
        sdk: ZetaSdkClient,
        targetUrl: String,
        builder: ZetaHttpClientBuilder.() -> Unit = {},
        customHeaders: Map<String, String>,
        handler: WsAsyncSession.WsHandler,
    ): CompletableFuture<Unit> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return scope.future {
            try {
                sdk.ws(targetUrl, builder, customHeaders = customHeaders) {
                    val asyncSession = WsAsyncSession(this, scope)
                    val handlerFuture = handler.handle(asyncSession)
                    handlerFuture.await()
                    asyncSession.awaitClose().await()
                }
            } finally {
                scope.cancel()
            }
        }
    }

    class WsAsyncSession(
        private val session: WebSocketSession,
        private val scope: CoroutineScope,
    ) {
        private val listeners = CopyOnWriteArrayList<WsMessageListener>()

        @Volatile
        private var messageLoopFuture: CompletableFuture<Unit>? = null
        private val maxFramesQueued = 64
        private val sendChannel = Channel<Frame>(capacity = maxFramesQueued)

        init {
            scope.launch {
                for (frame in sendChannel) {
                    session.send(frame)
                }
            }

            messageLoopFuture = scope.future {
                val listenerJobs = mutableListOf<Job>()
                try {
                    for (frame in session.incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                Log.i { "Received text frame: $text" }
                                listenerJobs.addAll(notifyListeners { onText(text) })
                            }

                            is Frame.Binary -> {
                                val bytes = frame.readBytes()
                                Log.i { "Received binary frame size: ${bytes.size}" }
                                listenerJobs.addAll(notifyListeners { onBinary(bytes) })
                            }

                            is Frame.Close -> {
                                Log.i { "Received close frame" }
                                listenerJobs.addAll(notifyListeners { onClose() })
                                break
                            }

                            else -> Log.i { "Ignoring frame: ${frame::class.simpleName}" }
                        }
                    }
                } catch (e: Exception) {
                    Log.e { "Exception during incoming frame: $e" }
                    listeners.forEach { it.onError(e) }
                    throw e
                } finally {
                    listenerJobs.joinAll()
                    sendChannel.close()
                }
            }
        }

        @JvmName("sendTextAsync")
        fun sendText(text: String): CompletableFuture<Unit> {
            return scope.future {
                Log.i { "Sending text frame: $text" }
                sendChannel.send(Frame.Text(text))
            }
        }

        @JvmName("sendBinaryAsync")
        fun sendBinary(data: ByteArray): CompletableFuture<Unit> {
            return scope.future {
                Log.i { "Sending binary frame size: ${data.size}" }
                sendChannel.send(Frame.Binary(true, data))
            }
        }

        @JvmName("onMessageAsync")
        fun onMessage(listener: WsMessageListener): CompletableFuture<Unit> {
            listeners.add(listener)
            return messageLoopFuture ?: CompletableFuture.completedFuture(Unit)
        }

        @JvmName("removeListenerAsync")
        fun removeListener(listener: WsMessageListener) {
            listeners.remove(listener)
        }

        @JvmName("closeAsync")
        fun close(): CompletableFuture<Unit> {
            return scope.future {
                Log.i { "Closing websocket session" }
                session.close()
            }
        }

        @JvmName("awaitCloseAsync")
        fun awaitClose(): CompletableFuture<Unit> {
            return messageLoopFuture ?: CompletableFuture.completedFuture(Unit)
        }

        @OptIn(DelicateCoroutinesApi::class)
        fun isActive(): Boolean {
            return !session.outgoing.isClosedForSend && !session.incoming.isClosedForReceive
        }

        fun interface WsHandler {
            fun handle(session: WsAsyncSession): CompletableFuture<Unit>
        }

        private fun notifyListeners(block: suspend WsMessageListener.() -> Unit): List<Job> {
            return listeners.map { l ->
                scope.launch {
                    try {
                        l.block()
                    } catch (e: Exception) {
                        Log.w { "Listener error: ${e.message}" }
                    }
                }
            }
        }

        interface WsMessageListener {
            fun onText(text: String)
            fun onBinary(data: ByteArray)
            fun onClose()
            fun onError(error: Throwable)
        }
    }
}
