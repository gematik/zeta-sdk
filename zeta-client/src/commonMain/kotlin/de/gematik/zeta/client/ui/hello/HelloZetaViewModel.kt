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

import com.ensody.reactivestate.ExperimentalReactiveStateApi
import com.ensody.reactivestate.ReactiveViewModel
import de.gematik.zeta.client.data.repository.HelloZetaRepository
import de.gematik.zeta.client.data.repository.HelloZetaRepositoryImpl
import de.gematik.zeta.sdk.notifications.model.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalReactiveStateApi::class)
public class HelloZetaViewModel(
    scope: CoroutineScope,
    private val repository: HelloZetaRepository = HelloZetaRepositoryImpl(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReactiveViewModel(scope) {
    private val _lastResult: MutableStateFlow<String> = MutableStateFlow("")
    public val lastResult: StateFlow<String> = _lastResult.asStateFlow()

    private val _loadingAction: MutableStateFlow<SdkAction?> = MutableStateFlow(null)
    public val loadingAction: StateFlow<SdkAction?> = _loadingAction.asStateFlow()

    private val _pusherConfigured: MutableStateFlow<Boolean> = MutableStateFlow(repository.isPusherConfigured())
    public val pusherConfigured: StateFlow<Boolean> = _pusherConfigured.asStateFlow()

    private val _subscribedChannels: MutableStateFlow<List<Channel>> = MutableStateFlow(emptyList())
    public val subscribedChannels: StateFlow<List<Channel>> = _subscribedChannels.asStateFlow()

    internal fun helloZeta() = runAction(SdkAction.HELLO_ZETA) {
        repository.helloZeta()
    }

    internal fun doAuthentication() = runAction(SdkAction.AUTHENTICATE) {
        repository.doAuthentication()
        "Authenticated"
    }

    internal fun doRegistration() = runAction(SdkAction.REGISTER) {
        repository.doRegistration()
        "Registered"
    }

    internal fun doDiscovery() = runAction(SdkAction.DISCOVER) {
        repository.doDiscovery()
        "Discovery complete"
    }

    internal fun forgetAuthorization() = runAction(SdkAction.FORGET) {
        repository.forgetAuthorization()
        "Forgot authorization"
    }

    internal fun forgetRegistration() = runAction(SdkAction.CLEAR_REGISTRATION) {
        repository.forgetRegistration()
        "Cleared registration"
    }

    internal fun statusSdk() = runAction(SdkAction.STATUS) {
        repository.status().toString()
    }

    internal fun logoutAuthorization() = runAction(SdkAction.LOGOUT) {
        repository.logoutAuthorization()
        "Logged out"
    }

    internal fun registerTestPusher() = runAction(SdkAction.TEST_PUSHER) {
        repository.registerTestPusher()
    }

    internal fun subscribeAllChannels() = runAction(SdkAction.SUBSCRIBE_ALL_CHANNELS) {
        val channels = repository.subscribeAllChannels()
        _subscribedChannels.update { channels }
        "Subscribed all channels (${channels.size})"
    }

    internal fun unsubscribeAllChannels() = runAction(SdkAction.UNSUBSCRIBE_ALL_CHANNELS) {
        val result = repository.unsubscribeAllChannels()
        _subscribedChannels.update { emptyList() }
        result
    }

    internal fun changeEmail(newEmail: String) = runAction(SdkAction.CHANGE_EMAIL) {
        repository.changeEmail(newEmail)
    }

    private var currentJob: Job? = null

    private fun runAction(action: SdkAction, block: suspend () -> Any?) {
        currentJob = launch(ioDispatcher) {
            _loadingAction.update { action }
            try {
                val result = block()
                _lastResult.update { result?.toString() ?: "OK" }
            } catch (t: CancellationException) {
                _lastResult.update { "Cancelled" }
                throw t
            } catch (t: Throwable) {
                _lastResult.update { "Error: ${t.message ?: t::class.simpleName}" }
            } finally {
                _loadingAction.update { null }
                _pusherConfigured.update { repository.isPusherConfigured() }
            }
        }
    }

    internal fun cancelCurrentAction() {
        currentJob?.cancel()
        _loadingAction.update { null }
    }

    public enum class SdkAction {
        DISCOVER, REGISTER, AUTHENTICATE, LOGOUT, CLEAR_REGISTRATION, FORGET, STATUS, HELLO_ZETA, TEST_PUSHER,
        SUBSCRIBE_ALL_CHANNELS, UNSUBSCRIBE_ALL_CHANNELS, CHANGE_EMAIL,
    }
}
