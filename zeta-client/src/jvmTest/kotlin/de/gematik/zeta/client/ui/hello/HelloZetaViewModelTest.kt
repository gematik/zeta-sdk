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
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun changeEmail_showsSdkStatus() = testScope.runTest {
        coEvery { mockRepository.changeEmail("new@example.de") } returns "Email change verified"

        viewModel.changeEmail("new@example.de")
        advanceUntilIdle()

        coVerify { mockRepository.changeEmail("new@example.de") }
        assertEquals("Email change verified", viewModel.lastResult.value)
    }

    @Test
    fun changeEmail_failure_showsError() = testScope.runTest {
        coEvery { mockRepository.changeEmail("bad") } throws IllegalStateException("new_email must not be blank")

        viewModel.changeEmail("bad")
        advanceUntilIdle()

        assertTrue(viewModel.lastResult.value.startsWith("Error:"))
    }
}
