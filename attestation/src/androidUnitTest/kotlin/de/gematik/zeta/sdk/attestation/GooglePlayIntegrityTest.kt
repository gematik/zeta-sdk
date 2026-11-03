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

package de.gematik.zeta.sdk.attestation

import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val CLOUD_PROJECT_NUMBER = 880503825870L
private const val NONCE = "bm9uY2Utbm9uY2Utbm9uY2U"

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePlayIntegrityTest {
    private val classicManager = mockk<IntegrityManager>()
    private val standardManager = mockk<StandardIntegrityManager>()

    private fun sut() = GooglePlayIntegrity(CLOUD_PROJECT_NUMBER, { classicManager }, { standardManager })

    @Suppress("UNCHECKED_CAST")
    private fun <T> task(): Task<T> = mockk<Task<Any?>>() as Task<T>

    private fun <T> completed(task: Task<T>): Task<T> {
        every { task.addOnCompleteListener(any<Executor>(), any<OnCompleteListener<T>>()) } answers {
            secondArg<OnCompleteListener<T>>().onComplete(task)
            task
        }
        return task
    }

    private fun <T> succeeded(value: T): Task<T> {
        val task = task<T>()
        every { task.isSuccessful } returns true
        every { task.result } returns value
        return completed(task)
    }

    private fun <T> failed(error: Exception): Task<T> {
        val task = task<T>()
        every { task.isSuccessful } returns false
        every { task.exception } returns error
        return completed(task)
    }

    private fun <T> cancelled(): Task<T> {
        val task = task<T>()
        every { task.isSuccessful } returns false
        every { task.exception } returns null
        return completed(task)
    }

    private fun <T> pending(): Task<T> = task<T>().also {
        every { it.addOnCompleteListener(any<Executor>(), any<OnCompleteListener<T>>()) } returns it
    }

    /** A task whose completion the test triggers by calling the returned function. */
    private fun <T> deferred(value: T): Pair<Task<T>, () -> Unit> {
        val task = task<T>()
        val listener = slot<OnCompleteListener<T>>()
        every { task.isSuccessful } returns true
        every { task.result } returns value
        every { task.addOnCompleteListener(any<Executor>(), capture(listener)) } returns task
        return task to { listener.captured.onComplete(task) }
    }

    private fun classicResponse(value: String): IntegrityTokenResponse = mockk { every { token() } returns value }
    private fun integrityToken(value: String): StandardIntegrityToken = mockk { every { token() } returns value }

    // StandardIntegrityException has no public constructor; coroutines stack-trace recovery reads its cause.
    private fun standardError(code: Int): StandardIntegrityException = mockk(relaxed = true) {
        every { errorCode } returns code
        every { cause } returns null
    }

    private fun provider(vararg results: Task<StandardIntegrityToken>): StandardIntegrityTokenProvider =
        mockk { every { request(any()) } returnsMany results.toList() }

    @Test
    fun requestIntegrityToken_returnsGoogleToken_forNonceAndProjectNumber() = runTest {
        val request = slot<IntegrityTokenRequest>()
        every { classicManager.requestIntegrityToken(capture(request)) } returns succeeded(classicResponse("classic"))

        assertEquals("classic", sut().requestIntegrityToken(NONCE))
        assertEquals(NONCE, request.captured.nonce())
        assertEquals(CLOUD_PROJECT_NUMBER, request.captured.cloudProjectNumber())
    }

    @Test
    fun requestIntegrityToken_googleError_returnsNull() = runTest {
        every { classicManager.requestIntegrityToken(any()) } returns failed(IllegalStateException("network"))

        assertNull(sut().requestIntegrityToken(NONCE))
    }

    @Test
    fun requestIntegrityToken_cancelledTask_returnsNull() = runTest {
        every { classicManager.requestIntegrityToken(any()) } returns cancelled()

        assertNull(sut().requestIntegrityToken(NONCE))
    }

    @Test
    fun requestIntegrityToken_timeout_returnsNull() = runTest {
        every { classicManager.requestIntegrityToken(any()) } returns pending()

        assertNull(sut().requestIntegrityToken(NONCE))
    }

    @Test
    fun requestIntegrityToken_managerCreationFails_returnsNull() = runTest {
        val sut = GooglePlayIntegrity(CLOUD_PROJECT_NUMBER, { throw IllegalStateException("no Play services") }, { standardManager })

        assertNull(sut.requestIntegrityToken(NONCE))
    }

    @Test
    fun requestIntegrityToken_callerCancelled_propagatesCancellation() = runTest {
        every { classicManager.requestIntegrityToken(any()) } returns pending()
        var continuedAfterCall = false

        val job = launch {
            sut().requestIntegrityToken(NONCE)
            continuedAfterCall = true
        }
        runCurrent()
        job.cancel()
        job.join()

        assertFalse(continuedAfterCall)
    }

    @Test
    fun requestStandardToken_preparesOnce_andBindsRequestHash() = runTest {
        val requests = mutableListOf<StandardIntegrityTokenRequest>()
        val provider = mockk<StandardIntegrityTokenProvider> {
            every { request(capture(requests)) } returnsMany listOf(succeeded(integrityToken("t1")), succeeded(integrityToken("t2")))
        }
        val prepare = slot<StandardIntegrityManager.PrepareIntegrityTokenRequest>()
        every { standardManager.prepareIntegrityToken(capture(prepare)) } returns succeeded(provider)
        val sut = sut()

        assertEquals("t1", sut.requestStandardToken("h1"))
        assertEquals("t2", sut.requestStandardToken("h2"))

        verify(exactly = 1) { standardManager.prepareIntegrityToken(any()) }
        // Play Integrity 1.6.0 ships PrepareIntegrityTokenRequest with obfuscated accessors; b() is cloudProjectNumber.
        assertEquals(CLOUD_PROJECT_NUMBER, prepare.captured.b())
        assertEquals(listOf("h1", "h2"), requests.map { it.requestHash() })
    }

    @Test
    fun requestStandardToken_concurrentCalls_prepareOnce() = runTest {
        val (prepareTask, completePrepare) = deferred(provider(succeeded(integrityToken("t1")), succeeded(integrityToken("t2"))))
        every { standardManager.prepareIntegrityToken(any()) } returns prepareTask
        val sut = sut()

        val first = async { sut.requestStandardToken("h1") }
        val second = async { sut.requestStandardToken("h2") }
        runCurrent()
        completePrepare()

        assertEquals(setOf("t1", "t2"), setOf(first.await(), second.await()))
        verify(exactly = 1) { standardManager.prepareIntegrityToken(any()) }
    }

    @Test
    fun requestStandardToken_failedPrepare_isNotCached() = runTest {
        every { standardManager.prepareIntegrityToken(any()) } returnsMany listOf(
            failed<StandardIntegrityTokenProvider>(standardError(StandardIntegrityErrorCode.NETWORK_ERROR)),
            succeeded(provider(succeeded(integrityToken("t")))),
        )
        val sut = sut()

        assertNull(sut.requestStandardToken("h1"))
        assertEquals("t", sut.requestStandardToken("h2"))
    }

    @Test
    fun requestStandardToken_providerInvalid_returnsNull_andNextCallPreparesAgain() = runTest {
        val stale = provider(failed(standardError(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID)))
        val fresh = provider(succeeded(integrityToken("fresh")))
        every { standardManager.prepareIntegrityToken(any()) } returnsMany listOf(succeeded(stale), succeeded(fresh))
        val sut = sut()

        assertNull(sut.requestStandardToken("h1"))
        verify(exactly = 1) { standardManager.prepareIntegrityToken(any()) }
        assertEquals("fresh", sut.requestStandardToken("h2"))
        verify(exactly = 2) { standardManager.prepareIntegrityToken(any()) }
    }

    @Test
    fun requestStandardToken_otherRequestError_keepsCachedProvider() = runTest {
        val provider = provider(
            failed(standardError(StandardIntegrityErrorCode.NETWORK_ERROR)),
            succeeded(integrityToken("t")),
        )
        every { standardManager.prepareIntegrityToken(any()) } returns succeeded(provider)
        val sut = sut()

        assertNull(sut.requestStandardToken("h1"))
        assertEquals("t", sut.requestStandardToken("h2"))
        verify(exactly = 1) { standardManager.prepareIntegrityToken(any()) }
    }

    @Test
    fun requestStandardToken_timeout_returnsNull() = runTest {
        every { standardManager.prepareIntegrityToken(any()) } returns pending()

        assertNull(sut().requestStandardToken("h"))
    }

    @Test
    fun disabled_returnsNull() = runTest {
        assertNull(PlayIntegrity.Disabled.requestIntegrityToken(NONCE))
        assertNull(PlayIntegrity.Disabled.requestStandardToken("h"))
    }
}
