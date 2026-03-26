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

@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@file:Suppress("FunctionNaming")
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

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import interop.ZetaSdk_Client
import interop.ZetaSdk_HttpClient
import interop.ZetaSdk_HttpHeader
import interop.ZetaSdk_WSMessage
import interop.ZetaSdk_WSSession
import interop.ZetaSdk_WsMessageType
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.memcpy
import kotlin.experimental.ExperimentalNativeApi

@CName(externName = "ZetaSdk_WSSession_create")
fun ZetaSdk_WSSession_create(
    sdkClient: CPointer<ZetaSdk_HttpClient>,
    url: CPointer<ByteVar>,
    urlLen: Int,
    handler: CPointer<CFunction<(CPointer<ZetaSdk_WSSession>) -> Unit>>,
) {
    val zetaHttpClient = sdkClient.pointed.zetaHttpClient!!.asStableRef<ZetaHttpClient>().get()
    val targetUrl = url.readBytes(urlLen).decodeToString()

    runBlocking {
        zetaHttpClient.webSocket(
            request = { url(targetUrl) },
            block = {
                val cWsSession = nativeHeap.alloc<ZetaSdk_WSSession>().let { cSession ->
                    cSession.zetaSdkWsSession = StableRef.create(this).asCPointer()
                    cSession.ptr
                }
                handler.invoke(cWsSession)
            },
        )
    }
}

@CName(externName = "ZetaSdk_Client_ws")
fun ZetaSdk_Client_ws(
    sdkClient: CPointer<ZetaSdk_Client>,
    url: CPointer<ByteVar>,
    urlLen: Int,
    handler: CPointer<CFunction<(CPointer<ZetaSdk_WSSession>) -> Unit>>,
    customHeaders: CPointer<ZetaSdk_HttpHeader>?,
    customHeaderCount: Int,
) {
    val zetaHttpClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    val targetUrl = url.readBytes(urlLen).decodeToString()
    val headers = customHeaders
        ?.toKList(customHeaderCount)
        ?.associate {
            val key = it?.key?.toKString() ?: ""
            val value = it?.value?.toKString() ?: ""
            key to value
        } ?: emptyMap()

    runBlocking {
        zetaHttpClient.ws(
            targetUrl = targetUrl,
            builder = {
                disableServerValidation(true)
            },
            block = {
                val cWsSession = nativeHeap.alloc<ZetaSdk_WSSession>().let { cSession ->
                    cSession.zetaSdkWsSession = StableRef.create(this).asCPointer()
                    cSession.ptr
                }
                handler.invoke(cWsSession)
            },
            customHeaders = headers,
        )
    }
}

@CName(externName = "ZetaSdk_WSSession_close")
fun ZetaSdk_WSSession_close(
    wsSession: CPointer<ZetaSdk_WSSession>,
) {
    wsSession.pointed.zetaSdkWsSession!!.asStableRef<DefaultClientWebSocketSession>().let { cSession ->
        cSession.get().let { session ->
            runBlocking {
                session.close()
            }
        }
        cSession.dispose()
    }
    nativeHeap.free(wsSession.rawValue)
}

@CName(externName = "ZetaSdk_WSSession_receiveNext")
fun ZetaSdk_WSSession_receiveNext(
    wsSession: CPointer<ZetaSdk_WSSession>,
): CPointer<ZetaSdk_WSMessage>? {
    val session = wsSession.pointed.zetaSdkWsSession!!.asStableRef<DefaultClientWebSocketSession>().get()
    return runBlocking {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> {
                    val text = frame.readText()
                    return@runBlocking nativeHeap.alloc<ZetaSdk_WSMessage>().also { cWsMessage ->
                        cWsMessage.type = ZetaSdk_WsMessageType.WS_TEXT
                        val bytes = text.encodeToByteArray()
                        val buf = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
                        bytes.forEachIndexed { i, b -> buf[i] = b }
                        buf[bytes.size] = 0
                        cWsMessage.data.text.text = buf
                    }.ptr
                }

                is Frame.Binary -> {
                    val bytes = frame.readBytes()
                    return@runBlocking nativeHeap.alloc<ZetaSdk_WSMessage>().also { cWsMessage ->
                        cWsMessage.type = ZetaSdk_WsMessageType.WS_BINARY
                        cWsMessage.data.binary.bytes = nativeHeap.allocArray<ByteVar>(bytes.size)
                        cWsMessage.data.binary.size = bytes.size
                        memcpy(cWsMessage.data.binary.bytes, bytes.refTo(0), bytes.size.toULong())
                    }.ptr
                }

                is Frame.Close -> {
                    return@runBlocking nativeHeap.alloc<ZetaSdk_WSMessage>().also { cWsMessage ->
                        cWsMessage.type = ZetaSdk_WsMessageType.WS_CLOSE
                    }.ptr
                }

                else -> {
                    // ignore other Frames
                }
            }
        }
        return@runBlocking null
    }
}

@CName(externName = "ZetaSdk_WSSession_sendText")
fun ZetaSdk_WSSession_sendText(
    wsSession: CPointer<ZetaSdk_WSSession>,
    text: CPointer<ByteVar>,
    textLen: Int,
) {
    val session = wsSession.pointed.zetaSdkWsSession!!.asStableRef<DefaultClientWebSocketSession>().get()
    runBlocking {
        val message = text.readBytes(textLen).decodeToString()
        session.send(Frame.Text(message))
    }
}

@CName(externName = "ZetaSdk_WSSession_sendBinary")
fun ZetaSdk_WSSession_sendBinary(
    wsSession: CPointer<ZetaSdk_WSSession>,
    binary: CPointer<ByteVar>,
    size: Int,
) {
    val session = wsSession.pointed.zetaSdkWsSession!!.asStableRef<DefaultClientWebSocketSession>().get()
    runBlocking {
        session.send(Frame.Binary(true, binary.readBytes(size)))
    }
}

@CName(externName = "ZetaSdk_WSMessage_destroy")
fun ZetaSdk_WSMessage_destroy(
    wsMessage: CPointer<ZetaSdk_WSMessage>,
) {
    when (wsMessage.pointed.type) {
        ZetaSdk_WsMessageType.WS_TEXT -> {
            nativeHeap.free(wsMessage.pointed.data.text.text!!.rawValue)
        }

        ZetaSdk_WsMessageType.WS_BINARY -> {
            nativeHeap.free(wsMessage.pointed.data.binary.bytes!!.rawValue)
        }

        else -> {
            // ignore other types
        }
    }
    nativeHeap.free(wsMessage.rawValue)
}
