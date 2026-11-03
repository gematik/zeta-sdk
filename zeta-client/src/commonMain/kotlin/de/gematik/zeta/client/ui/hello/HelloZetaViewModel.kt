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
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.identity.InvalidClientManagementRequestException
import de.gematik.zeta.sdk.authentication.identity.ProblemCode
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient
import de.gematik.zeta.sdk.notifications.NotificationHistoryNotFoundException
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.HistoricNotification
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

    private val _history: MutableStateFlow<List<HistoricNotification>> = MutableStateFlow(emptyList())
    public val history: StateFlow<List<HistoricNotification>> = _history.asStateFlow()

    /** The history entry shown in the detail dialog; `null` while the dialog is closed. */
    private val _historyDetail: MutableStateFlow<HistoryDetail?> = MutableStateFlow(null)
    public val historyDetail: StateFlow<HistoryDetail?> = _historyDetail.asStateFlow()

    private var historyDetailJob: Job? = null

    private val _registeredClients: MutableStateFlow<List<RegisteredClient>> = MutableStateFlow(emptyList())
    public val registeredClients: StateFlow<List<RegisteredClient>> = _registeredClients.asStateFlow()

    private val _emailOtpPrompt: MutableStateFlow<EmailOtpPrompt?> = MutableStateFlow(null)
    public val emailOtpPrompt: StateFlow<EmailOtpPrompt?> = _emailOtpPrompt.asStateFlow()

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

    internal fun listClients() = runAction(SdkAction.LIST_CLIENTS) {
        val result = repository.listClients()
        _registeredClients.value = result
        "${result.size} client(s) fetched"
    }

    internal fun deleteClient(id: String) = runAction(SdkAction.DELETE_CLIENT) {
        repository.deleteClient(id)
        if (repository.status() == SdkStatus.NOT_REGISTERED) {
            _registeredClients.value = emptyList()
            "Own client deleted"
        } else {
            _registeredClients.value = repository.listClients()
            "Client $id deleted"
        }
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

    internal fun loadHistory() = runAction(SdkAction.LOAD_HISTORY) {
        // The Notification Service appends to the history and returns it oldest first (its behavior,
        // not a spec guarantee); the response carries no timestamp, so reversing is the best we can do.
        val entries = repository.getHistory().asReversed()
        _history.update { entries }
        "History: ${entries.size} entries"
    }

    /** Fetches [identifier] by ID (`GET /history/{notification_id}`) for the detail dialog. */
    internal fun openHistoricNotification(identifier: String) {
        historyDetailJob?.cancel()
        _historyDetail.update { HistoryDetail.Loading }
        historyDetailJob = launch(ioDispatcher) {
            val detail = try {
                HistoryDetail.Loaded(repository.getHistoricNotification(identifier))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                HistoryDetail.Failed(t.describe())
            }
            _historyDetail.update { detail }
        }
    }

    internal fun closeHistoricNotification() {
        historyDetailJob?.cancel()
        _historyDetail.update { null }
    }

    internal fun changeEmail(newEmail: String) = runAction(SdkAction.CHANGE_EMAIL) {
        when (val response = repository.changeEmail(newEmail)) {
            is ChangeEmailResponse.Pending -> {
                _emailOtpPrompt.update { EmailOtpPrompt(response.emailHint) }
                "Email change pending"
            }
            ChangeEmailResponse.Verified -> "Email change verified"
        }
    }

    internal fun resendEmailChangeCode() = runAction(SdkAction.CHANGE_EMAIL) {
        val challenge = repository.resendEmailChangeCode()
        _emailOtpPrompt.update { EmailOtpPrompt(challenge.emailHint) }
        "Code re-sent"
    }

    internal fun verifyEmailChange(code: String) = runAction(SdkAction.CHANGE_EMAIL) {
        try {
            repository.verifyEmailChange(code)
        } catch (e: InvalidClientManagementRequestException) {
            if (e.problem?.code == ProblemCode.INVALID_CODE) _emailOtpPrompt.update { it?.copy(rejected = true) }
            throw e
        }
        _emailOtpPrompt.update { null }
        "Email verified"
    }

    internal fun cancelEmailChange() {
        _emailOtpPrompt.update { null }
    }

    private var currentJob: Job? = null

    private fun runAction(action: SdkAction, block: suspend () -> Any?) {
        if (!action.showsClients) _registeredClients.value = emptyList()

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
        SUBSCRIBE_ALL_CHANNELS, UNSUBSCRIBE_ALL_CHANNELS, LOAD_HISTORY, CHANGE_EMAIL, LIST_CLIENTS, DELETE_CLIENT;

        internal val showsClients: Boolean
            get() = this == LIST_CLIENTS || this == DELETE_CLIENT
    }

    public data class EmailOtpPrompt(val emailHint: String?, val rejected: Boolean = false)

    public sealed interface HistoryDetail {
        public data object Loading : HistoryDetail
        public data class Loaded(val notification: HistoricNotification) : HistoryDetail
        public data class Failed(val message: String) : HistoryDetail
    }
}

/** Surfaces the Notification Service's error code (e.g. `NOTIFICATION_NOT_FOUND`) next to the message. */
private fun Throwable.describe(): String = when (this) {
    is NotificationHistoryNotFoundException -> listOfNotNull(errorCode, errorDetail).joinToString(": ").ifEmpty { message.orEmpty() }
    else -> message ?: this::class.simpleName.orEmpty()
}
