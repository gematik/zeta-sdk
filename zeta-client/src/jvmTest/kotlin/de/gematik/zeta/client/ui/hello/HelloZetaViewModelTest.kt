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

package de.gematik.zeta.client.ui.hello

import com.ensody.reactivestate.test.contextualBackgroundScope
import de.gematik.zeta.client.data.repository.HelloZetaRepository
import de.gematik.zeta.sdk.authentication.identity.ChallengeType
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.identity.EmailChallenge
import de.gematik.zeta.sdk.authentication.identity.ErrorBody
import de.gematik.zeta.sdk.authentication.identity.InvalidClientManagementRequestException
import de.gematik.zeta.sdk.authentication.identity.ProblemCode
import de.gematik.zeta.sdk.authentication.identity.ProblemDetails
import de.gematik.zeta.sdk.notifications.NotificationHistoryNotFoundException
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
import de.gematik.zeta.sdk.notifications.model.HistoricNotification
import io.ktor.client.statement.HttpResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HelloZetaViewModelTest {

    private lateinit var mockRepository: HelloZetaRepository
    private lateinit var testScope: TestScope
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var viewModel: HelloZetaViewModel

    @BeforeTest
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)

        mockRepository = mockk()
        every { mockRepository.isPusherConfigured() } returns false

        viewModel = HelloZetaViewModel(testScope.contextualBackgroundScope(), mockRepository, testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pusherConfigured_isTrue_whenRepositoryAlreadyHasKey() {
        every { mockRepository.isPusherConfigured() } returns true
        viewModel = HelloZetaViewModel(testScope.contextualBackgroundScope(), mockRepository, testDispatcher)
        assertTrue(viewModel.pusherConfigured.value)
    }

    @Test
    fun pusherConfigured_isFalse_untilTestPusherSucceeds() = testScope.runTest {
        assertFalse(viewModel.pusherConfigured.value)

        coEvery { mockRepository.registerTestPusher() } answers {
            every { mockRepository.isPusherConfigured() } returns true
            "Pusher registered (…abcd1234)"
        }

        viewModel.registerTestPusher()
        advanceUntilIdle()

        assertTrue(viewModel.pusherConfigured.value)
        assertEquals("Pusher registered (…abcd1234)", viewModel.lastResult.value)
    }

    @Test
    fun registerTestPusher_failure_keepsChannelButtonsDisabled() = testScope.runTest {
        coEvery { mockRepository.registerTestPusher() } throws IllegalStateException("FCM token unavailable")

        viewModel.registerTestPusher()
        advanceUntilIdle()

        assertFalse(viewModel.pusherConfigured.value)
        assertTrue(viewModel.lastResult.value.startsWith("Error:"))
    }

    @Test
    fun forgetAuthorization_disablesChannelButtons() = testScope.runTest {
        coEvery { mockRepository.registerTestPusher() } answers {
            every { mockRepository.isPusherConfigured() } returns true
            "Pusher registered (…abcd1234)"
        }
        coEvery { mockRepository.forgetAuthorization() } answers {
            every { mockRepository.isPusherConfigured() } returns false
        }

        viewModel.registerTestPusher()
        advanceUntilIdle()
        assertTrue(viewModel.pusherConfigured.value)

        viewModel.forgetAuthorization()
        advanceUntilIdle()
        assertFalse(viewModel.pusherConfigured.value)
    }

    @Test
    fun subscribeAllChannels_showsReturnedChannels() = testScope.runTest {
        val channels = listOf(
            Channel("chat", ChannelStatus.ENABLED),
            Channel("alerts", ChannelStatus.ENABLED),
        )
        coEvery { mockRepository.subscribeAllChannels() } returns channels

        viewModel.subscribeAllChannels()
        advanceUntilIdle()

        coVerify { mockRepository.subscribeAllChannels() }
        assertEquals(channels, viewModel.subscribedChannels.value)
        assertEquals("Subscribed all channels (2)", viewModel.lastResult.value)
    }

    @Test
    fun unsubscribeAllChannels_clearsChannelList() = testScope.runTest {
        val channels = listOf(Channel("chat", ChannelStatus.ENABLED))
        coEvery { mockRepository.subscribeAllChannels() } returns channels
        coEvery { mockRepository.unsubscribeAllChannels() } returns "Unsubscribed all channels (1)"

        viewModel.subscribeAllChannels()
        advanceUntilIdle()
        viewModel.unsubscribeAllChannels()
        advanceUntilIdle()

        coVerify { mockRepository.unsubscribeAllChannels() }
        assertTrue(viewModel.subscribedChannels.value.isEmpty())
        assertEquals("Unsubscribed all channels (1)", viewModel.lastResult.value)
    }

    @Test
    fun loadHistory_showsEntriesNewestFirst() = testScope.runTest {
        val oldest = historicNotification("id-1", "first")
        val newest = historicNotification("id-2", "second")
        coEvery { mockRepository.getHistory() } returns listOf(oldest, newest)

        viewModel.loadHistory()
        advanceUntilIdle()

        assertEquals(listOf(newest, oldest), viewModel.history.value)
        assertEquals("History: 2 entries", viewModel.lastResult.value)
    }

    @Test
    fun loadHistory_failure_keepsPreviousEntries() = testScope.runTest {
        val entry = historicNotification("id-1", "first")
        coEvery { mockRepository.getHistory() } returns listOf(entry) andThenThrows IllegalStateException("No local pusher is registered")

        viewModel.loadHistory()
        advanceUntilIdle()
        viewModel.loadHistory()
        advanceUntilIdle()

        assertEquals(listOf(entry), viewModel.history.value)
        assertTrue(viewModel.lastResult.value.startsWith("Error:"))
    }

    @Test
    fun openHistoricNotification_showsEntryFetchedByIdUntilClosed() = testScope.runTest {
        val entry = historicNotification("id-1", "first")
        coEvery { mockRepository.getHistoricNotification("id-1") } returns entry

        viewModel.openHistoricNotification("id-1")
        advanceUntilIdle()

        coVerify { mockRepository.getHistoricNotification("id-1") }
        assertEquals(HelloZetaViewModel.HistoryDetail.Loaded(entry), viewModel.historyDetail.value)

        viewModel.closeHistoricNotification()

        assertNull(viewModel.historyDetail.value)
    }

    @Test
    fun openHistoricNotification_notFound_showsErrorCodeInDialog() = testScope.runTest {
        coEvery { mockRepository.getHistoricNotification("gone") } throws NotificationHistoryNotFoundException(
            mockk<HttpResponse>(relaxed = true),
            "NOTIFICATION_NOT_FOUND",
            "No notification is stored for the authenticated user",
        )

        viewModel.openHistoricNotification("gone")
        advanceUntilIdle()

        assertEquals(
            HelloZetaViewModel.HistoryDetail.Failed("NOTIFICATION_NOT_FOUND: No notification is stored for the authenticated user"),
            viewModel.historyDetail.value,
        )
    }

    @Test
    fun changeEmail_pending_opensOtpPromptWithHint() = testScope.runTest {
        coEvery { mockRepository.changeEmail("new@example.de") } returns
            ChangeEmailResponse.Pending(challengeType = ChallengeType.EMAIL_OTP, emailHint = "n***@example.de")

        viewModel.changeEmail("new@example.de")
        advanceUntilIdle()

        assertEquals(HelloZetaViewModel.EmailOtpPrompt(emailHint = "n***@example.de"), viewModel.emailOtpPrompt.value)
        assertEquals("Email change pending", viewModel.lastResult.value)
    }

    @Test
    fun changeEmail_alreadyVerified_opensNoPrompt() = testScope.runTest {
        coEvery { mockRepository.changeEmail("same@example.de") } returns ChangeEmailResponse.Verified

        viewModel.changeEmail("same@example.de")
        advanceUntilIdle()

        assertNull(viewModel.emailOtpPrompt.value)
        assertEquals("Email change verified", viewModel.lastResult.value)
    }

    @Test
    fun verifyEmailChange_success_closesPrompt() = testScope.runTest {
        openOtpPrompt()
        coEvery { mockRepository.verifyEmailChange("123456") } returns Unit

        viewModel.verifyEmailChange("123456")
        advanceUntilIdle()

        assertNull(viewModel.emailOtpPrompt.value)
        assertEquals("Email verified", viewModel.lastResult.value)
    }

    @Test
    fun verifyEmailChange_invalidCode_keepsPromptOpenAsRejected() = testScope.runTest {
        openOtpPrompt()
        coEvery { mockRepository.verifyEmailChange("000000") } throws
            InvalidClientManagementRequestException(mockk<HttpResponse>(relaxed = true), ErrorBody(problem = ProblemDetails(status = 400, code = ProblemCode.INVALID_CODE)))

        viewModel.verifyEmailChange("000000")
        advanceUntilIdle()

        assertEquals(HelloZetaViewModel.EmailOtpPrompt(emailHint = "n***@example.de", rejected = true), viewModel.emailOtpPrompt.value)
    }

    @Test
    fun resendEmailChangeCode_updatesHintAndClearsRejection() = testScope.runTest {
        openOtpPrompt()
        coEvery { mockRepository.verifyEmailChange("000000") } throws
            InvalidClientManagementRequestException(mockk<HttpResponse>(relaxed = true), ErrorBody(problem = ProblemDetails(status = 400, code = ProblemCode.INVALID_CODE)))
        viewModel.verifyEmailChange("000000")
        coEvery { mockRepository.resendEmailChangeCode() } returns EmailChallenge(ChallengeType.EMAIL_OTP, "x***@example.de")

        viewModel.resendEmailChangeCode()
        advanceUntilIdle()

        assertEquals(HelloZetaViewModel.EmailOtpPrompt(emailHint = "x***@example.de"), viewModel.emailOtpPrompt.value)
    }

    @Test
    fun cancelEmailChange_closesPrompt() = testScope.runTest {
        openOtpPrompt()

        viewModel.cancelEmailChange()

        assertNull(viewModel.emailOtpPrompt.value)
    }

    private fun openOtpPrompt() {
        coEvery { mockRepository.changeEmail("new@example.de") } returns
            ChangeEmailResponse.Pending(challengeType = ChallengeType.EMAIL_OTP, emailHint = "n***@example.de")
        viewModel.changeEmail("new@example.de")
    }

    @Test
    fun changeEmail_failure_showsError() = testScope.runTest {
        coEvery { mockRepository.changeEmail("bad") } throws IllegalStateException("new_email must not be blank")

        viewModel.changeEmail("bad")
        advanceUntilIdle()

        assertTrue(viewModel.lastResult.value.startsWith("Error:"))
    }

    private fun historicNotification(identifier: String, message: String) =
        HistoricNotification(identifier, buildJsonObject { put("message", message) })
}
