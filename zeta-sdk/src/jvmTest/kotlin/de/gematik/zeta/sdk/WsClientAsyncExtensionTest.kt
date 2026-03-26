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

import de.gematik.zeta.sdk.WsClientAsyncExtension.WsAsyncSession
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WsClientAsyncExtensionTest {
    private lateinit var mockSdkClient: ZetaSdkClient
    private lateinit var mockSession: DefaultClientWebSocketSession
    private lateinit var incomingChannel: Channel<Frame>
    private lateinit var outgoingChannel: Channel<Frame>
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(DelicateCoroutinesApi::class)
    @BeforeTest
    fun setup() {
        mockSdkClient = mockk()

        incomingChannel = Channel(Channel.UNLIMITED)
        outgoingChannel = Channel(Channel.UNLIMITED)

        mockSession = mockk {
            every { incoming } returns incomingChannel
            every { outgoing } returns outgoingChannel
        }

        coEvery { mockSession.send(any<Frame>()) } coAnswers {
            outgoingChannel.send(firstArg())
        }

        coEvery { mockSession.close() } coAnswers {
            incomingChannel.close()
            outgoingChannel.close()
        }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        incomingChannel.cancel()
        outgoingChannel.cancel()
    }

    @Test
    fun sendText_sendTextCalled_textFrameSent() = runTest {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val session = WsAsyncSession(mockSession, scope)

        session.sendText("Hello Zeta").get(1, TimeUnit.SECONDS)

        coVerify { mockSession.send(match<Frame.Text> { it.readText() == "Hello Zeta" }) }
        scope.cancel()
    }

    @Test
    fun sendBinary_sendBinaryCalled_binaryFrameSent() = runTest {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val session = WsAsyncSession(mockSession, scope)
        val data = byteArrayOf(1, 2, 3, 4, 5)

        // Act
        session.sendBinary(data).get(1, TimeUnit.SECONDS)

        // Assert
        coVerify { mockSession.send(match<Frame.Binary> { it.readBytes().contentEquals(data) }) }
        scope.cancel()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun isActive_sessionOpen_trueReturned() = runTest {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val session = WsAsyncSession(mockSession, scope)
        every { mockSession.outgoing.isClosedForSend } returns false
        every { mockSession.incoming.isClosedForReceive } returns false

        // Act
        val result = session.isActive()

        // Assert
        assertTrue(result)
        scope.cancel()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun isActive_sessionClosed_falseReturned() = runTest {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val session = WsAsyncSession(mockSession, scope)
        every { mockSession.outgoing.isClosedForSend } returns true
        every { mockSession.incoming.isClosedForReceive } returns true

        // Act
        val result = session.isActive()

        // Assert
        assertFalse(result)
        scope.cancel()
    }

    @Test
    fun awaitClose_messageLoopStarted_sameFutureReturned() = runTest {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val session = WsAsyncSession(mockSession, scope)
        val listener = object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) {}
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        }

        // Act
        val messageFuture = session.onMessage(listener)
        val awaitFuture = session.awaitClose()

        // Assert
        assertSame(messageFuture, awaitFuture)

        incomingChannel.send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "")))
        awaitFuture.get(2, TimeUnit.SECONDS)
        scope.cancel()
    }

    @Test
    fun awaitClose_incomingChannelClosed_futureCompletes() = runTest {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val session = WsAsyncSession(mockSession, scope)

        incomingChannel.close()

        // Act
        val awaitFuture = session.awaitClose()
        awaitFuture.get(500, TimeUnit.MILLISECONDS)

        // Assert
        assertTrue(awaitFuture.isDone)
        scope.cancel()
    }

    @Test
    fun wsAsync_sdkThrows_exceptionPropagated() = runTest {
        // Arrange
        val testException = RuntimeException("Connection failed")
        coEvery { mockSdkClient.ws<Unit>(any(), any(), any(), any()) } throws testException

        // Act
        val future = WsClientAsyncExtension.wsAsync(
            mockSdkClient,
            "wss://example.com/ws",
            {},
            emptyMap(),
        ) { session ->
            session.awaitClose()
        }
        // Assert
        val exception = assertFailsWith<Exception> { future.get(2, TimeUnit.SECONDS) }
        assertTrue(exception.cause is RuntimeException)
        assertTrue(
            exception.message?.contains("Connection failed") == true ||
                exception.cause?.message?.contains("Connection failed") == true,
        )
    }

    @Test
    fun onMessage_textFrameDelivered_listenerReceivesText() = runTest {
        // Arrange
        val fake = FakeWebSocketSession()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sut = WsAsyncSession(fake, scope)

        val texts = CopyOnWriteArrayList<String>()
        val listener = object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) { texts += text }
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        }

        // Acr
        val loopFuture = sut.onMessage(listener)
        fake.incomingCh.send(Frame.Text("hello"))
        fake.incomingCh.send(Frame.Close())

        loopFuture.get(2, TimeUnit.SECONDS)

        // Assert
        assertEquals(listOf("hello"), texts)
        scope.cancel()
    }

    @Test
    fun onMessage_binaryFrameDelivered_listenerReceivesBinary() = runTest {
        // Arrange
        val fake = FakeWebSocketSession()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sut = WsAsyncSession(fake, scope)

        val binaries = CopyOnWriteArrayList<ByteArray>()
        val listener = object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) {}
            override fun onBinary(data: ByteArray) { binaries += data }
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        }

        // Act
        val loopFuture = sut.onMessage(listener)
        fake.incomingCh.send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
        fake.incomingCh.send(Frame.Close())

        loopFuture.get(2, TimeUnit.SECONDS)

        // Assert
        assertEquals(1, binaries.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), binaries[0])
        scope.cancel()
    }

    @Test
    fun onMessage_closeFrameDelivered_listenerReceivesClose() = runTest {
        // Arrange
        val fake = FakeWebSocketSession()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val sut = WsAsyncSession(fake, scope)

        val closed = CountDownLatch(1)
        val listener = object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) {}
            override fun onBinary(data: ByteArray) {}
            override fun onClose() { closed.countDown() }
            override fun onError(error: Throwable) {}
        }

        // Act
        val loopFuture = sut.onMessage(listener)
        fake.incomingCh.send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "")))

        loopFuture.get(2, TimeUnit.SECONDS)

        // Assert
        assertTrue(closed.await(1, TimeUnit.SECONDS))
        scope.cancel()
    }

    @Test
    fun wsAsync_sendText_sendsFramesInCorrectOrder() = runTest {
        // Arrange
        val sentFrames = mutableListOf<String>()
        val latch = CountDownLatch(3)

        coEvery { mockSession.send(any<Frame>()) } answers {
            val frame = firstArg<Frame.Text>()
            sentFrames.add(frame.readText())
            latch.countDown()
        }

        // Act
        val session = WsAsyncSession(mockSession, scope)
        session.sendText("first").get()
        session.sendText("second").get()
        session.sendText("third").get()

        // Assert
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second", "third"), sentFrames)
    }

    @Test
    fun wsAsync_slowListener_doesNotBlockFastListener() = runBlocking {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeWebSocketSession()
        val session = WsAsyncSession(fake, scope)
        val fastReceived = CopyOnWriteArrayList<String>()
        val slowBlocker = Semaphore(permits = 1, acquiredPermits = 1)
        val slowStarted = CompletableDeferred<Unit>()
        val fastDone = CompletableDeferred<Unit>()

        session.onMessage(object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) {
                slowStarted.complete(Unit)
                runBlocking { slowBlocker.acquire() }
            }
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        })
        session.onMessage(object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) {
                fastReceived.add(text)
                fastDone.complete(Unit)
            }
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        })

        // Act
        fake.incomingCh.send(Frame.Text("hello"))

        withTimeout(2_000) { slowStarted.await() }
        withTimeout(2_000) { fastDone.await() }

        // Assert
        assertEquals(listOf("hello"), fastReceived)

        slowBlocker.release()
        scope.cancel()
    }

    @Test
    fun wsAsync_slowListener_doesNotBlockMultipleMessages() = runBlocking {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeWebSocketSession()
        val session = WsAsyncSession(fake, scope)
        val fastReceived = CopyOnWriteArrayList<String>()
        val received = Channel<String>(capacity = 3)
        val slowBlocker = Semaphore(permits = 1, acquiredPermits = 1)

        session.onMessage(object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) { runBlocking { slowBlocker.acquire() } }
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        })
        session.onMessage(object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) {
                fastReceived.add(text)
                received.trySend(text)
            }
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        })

        // Act
        fake.incomingCh.send(Frame.Text("message1"))
        fake.incomingCh.send(Frame.Text("message2"))
        fake.incomingCh.send(Frame.Text("message3"))

        // Assert
        withTimeout(2_000) {
            val results = listOf(received.receive(), received.receive(), received.receive())
            assertEquals(listOf("message1", "message2", "message3"), results.sorted())
        }

        slowBlocker.release()
        scope.cancel()
    }

    @Test
    fun wsAsync_listenerException_doesNotAffectOtherListeners() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeWebSocketSession()
        val session = WsAsyncSession(fake, scope)
        val fastReceived = CopyOnWriteArrayList<String>()
        val done = CompletableDeferred<Unit>()

        session.onMessage(object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) { error("slow crashed") }
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        })
        session.onMessage(object : WsAsyncSession.WsMessageListener {
            override fun onText(text: String) {
                fastReceived.add(text)
                done.complete(Unit)
            }
            override fun onBinary(data: ByteArray) {}
            override fun onClose() {}
            override fun onError(error: Throwable) {}
        })

        fake.incomingCh.send(Frame.Text("hello"))

        withTimeout(2_000) { done.await() }
        assertEquals(listOf("hello"), fastReceived)

        scope.cancel()
    }

    @Test
    fun parallelSessions_receiveIndependently() = runTest {
        // Arrange
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope1 = CoroutineScope(dispatcher + SupervisorJob())
        val scope2 = CoroutineScope(dispatcher + SupervisorJob())
        val s1 = FakeWebSocketSession()
        val s2 = FakeWebSocketSession()
        val ws1 = WsAsyncSession(s1, scope1)
        val ws2 = WsAsyncSession(s2, scope2)
        val r1 = CopyOnWriteArrayList<String>()
        val r2 = CopyOnWriteArrayList<String>()

        ws1.onMessage(testListener(onText = { r1 += it }))
        ws2.onMessage(testListener(onText = { r2 += it }))

        // Act
        s1.incomingCh.send(Frame.Text("a1"))
        s2.incomingCh.send(Frame.Text("b1"))
        s1.incomingCh.send(Frame.Text("a2"))
        s2.incomingCh.send(Frame.Text("b2"))
        s1.incomingCh.send(Frame.Close())
        s2.incomingCh.send(Frame.Close())
        s1.incomingCh.close()
        s2.incomingCh.close()

        val f1 = ws1.awaitClose()
        val f2 = ws2.awaitClose()

        testScheduler.advanceUntilIdle()

        // Assert
        assertTrue(f1.isDone)
        assertTrue(f2.isDone)
        assertEquals(listOf("a1", "a2"), r1)
        assertEquals(listOf("b1", "b2"), r2)

        scope1.cancel()
        scope2.cancel()
    }

    @Test
    fun parallelSessions_sendOrderingIsPerSession() = runTest {
        // Arrange
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope1 = CoroutineScope(dispatcher + SupervisorJob())
        val scope2 = CoroutineScope(dispatcher + SupervisorJob())
        val s1 = FakeWebSocketSession()
        val s2 = FakeWebSocketSession()
        val ws1 = WsAsyncSession(s1, scope1)
        val ws2 = WsAsyncSession(s2, scope2)

        // Act
        ws1.sendText("1a")
        ws2.sendText("2a")
        ws1.sendText("1b")
        ws2.sendText("2b")
        testScheduler.advanceUntilIdle()

        // Assert
        assertEquals(listOf("1a", "1b"), s1.sentTexts)
        assertEquals(listOf("2a", "2b"), s2.sentTexts)

        ws1.close().await()
        ws2.close().await()
        scope1.cancel()
        scope2.cancel()
    }

    @Test
    fun parallelSessions_realThreads() = runBlocking {
        // Arrange
        val scope1 = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scope2 = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val s1 = FakeWebSocketSession()
        val s2 = FakeWebSocketSession()
        val ws1 = WsAsyncSession(s1, scope1)
        val ws2 = WsAsyncSession(s2, scope2)
        val r1 = CopyOnWriteArrayList<String>()
        val r2 = CopyOnWriteArrayList<String>()
        val ws1Blocker = Semaphore(permits = 1, acquiredPermits = 1)
        val done1 = CompletableDeferred<Unit>()
        val done2 = CompletableDeferred<Unit>()

        ws1.onMessage(
            testListener(onText = { payload ->
                scope1.launch {
                    ws1Blocker.acquire()
                    r1 += payload
                    done1.complete(Unit)
                }
            }),
        )
        ws2.onMessage(
            testListener(onText = { payload ->
                scope2.launch {
                    r2 += payload
                    done2.complete(Unit)
                }
            }),
        )

        val close1 = ws1.awaitClose()
        ws2.awaitClose()

        // Act
        coroutineScope {
            launch(Dispatchers.Default) {
                s1.incomingCh.send(Frame.Text("a1"))
                s1.incomingCh.send(Frame.Close())
                s1.incomingCh.close()
            }
            launch(Dispatchers.Default) {
                s2.incomingCh.send(Frame.Text("b1"))
                s2.incomingCh.send(Frame.Close())
                s2.incomingCh.close()
            }
        }

        // Assert
        withTimeout(2_000) { done2.await() }
        assertEquals(listOf("b1"), r2)

        ws1Blocker.release()
        withTimeout(2_000) { done1.await() }
        withTimeout(2_000) { close1.await() }
        assertEquals(listOf("a1"), r1)

        scope1.cancel()
        scope2.cancel()
    }

    @Test
    fun incomingClosedWithCause_notifiesOnError() = runTest {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeWebSocketSession()
        val sut = WsAsyncSession(fake, scope)

        val errors = CopyOnWriteArrayList<String>()
        sut.onMessage(testListener(onError = { errors += it.message ?: it::class.java.name }))

        // Act
        fake.incomingCh.send(Frame.Text("hello"))
        fake.incomingCh.close(RuntimeException("boom"))

        // Assert
        val ex = assertFailsWith<Exception> { sut.awaitClose().await() }
        assertTrue((ex.cause?.message ?: ex.message).orEmpty().contains("boom"))
        assertTrue(errors.any { it.contains("boom") })

        scope.cancel()
    }

    @Test
    fun closeAsync_terminatesLoop_and_awaitCloseCompletes() = runTest {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeWebSocketSession()
        val sut = WsAsyncSession(fake, scope)

        val closed = CountDownLatch(1)
        sut.onMessage(testListener(onClose = { closed.countDown() }))

        sut.close().await()

        // Act
        fake.incomingCh.send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "")))
        fake.incomingCh.close()

        sut.awaitClose().await()

        // Assert
        assertTrue(closed.await(1, TimeUnit.SECONDS))

        scope.cancel()
    }

    @Test
    fun closeAsync_incomingClosed_awaitCloseCompletes() = runTest {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeWebSocketSession()
        val sut = WsAsyncSession(fake, scope)

        sut.close().await()
        fake.incomingCh.close()
        sut.awaitClose().await()

        scope.cancel()
    }

    @Test
    fun listenerThrows_doesNotAffectOtherListeners() = runBlocking {
        // Arrange
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fake = FakeWebSocketSession()
        val sut = WsAsyncSession(fake, scope)
        val received = CopyOnWriteArrayList<String>()

        sut.onMessage(testListener(onText = { error("listener crashed") }))
        sut.onMessage(testListener(onText = { received += it }))

        val closed = sut.awaitClose()

        // Act
        fake.incomingCh.send(Frame.Text("m1"))
        fake.incomingCh.send(Frame.Text("m2"))
        fake.incomingCh.send(Frame.Text("m3"))
        fake.incomingCh.send(Frame.Close())
        fake.incomingCh.close()

        withTimeout(2_000) { closed.await() }

        // Assert
        assertEquals(listOf("m1", "m2", "m3"), received)

        scope.cancel()
    }

    private fun testListener(
        onText: (String) -> Unit = {},
        onClose: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) = object : WsAsyncSession.WsMessageListener {
        override fun onText(text: String) = onText(text)
        override fun onBinary(data: ByteArray) {}
        override fun onClose() = onClose()
        override fun onError(error: Throwable) = onError(error)
    }
}

class FakeWebSocketSession(
    override var masking: Boolean = false,
    override var maxFrameSize: Long = Long.MAX_VALUE,
) : WebSocketSession {

    private val job = Job()
    val incomingCh = Channel<Frame>(Channel.UNLIMITED)
    val outgoingCh = Channel<Frame>(Channel.UNLIMITED)

    override val coroutineContext: CoroutineContext = Dispatchers.Unconfined + job

    override val incoming: ReceiveChannel<Frame> get() = incomingCh
    override val outgoing: SendChannel<Frame> get() = outgoingCh

    override val extensions: List<WebSocketExtension<*>> = emptyList()
    val sentTexts = CopyOnWriteArrayList<String>()

    override suspend fun send(frame: Frame) {
        outgoingCh.send(frame)
        if (frame is Frame.Text) {
            sentTexts += frame.readText()
        }
    }

    override suspend fun flush() {}

    @Deprecated(
        "Use cancel() instead.",
        replaceWith = ReplaceWith("cancel()", "kotlinx.coroutines.cancel"),
        level = DeprecationLevel.ERROR,
    )
    override fun terminate() {
        incomingCh.close()
        outgoingCh.close()
        job.cancel()
    }
}
