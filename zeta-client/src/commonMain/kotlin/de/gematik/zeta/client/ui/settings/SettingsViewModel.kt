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

package de.gematik.zeta.client.ui.settings

import com.ensody.reactivestate.ExperimentalReactiveStateApi
import com.ensody.reactivestate.ReactiveViewModel
import de.gematik.zeta.client.data.repository.SettingsRepository
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.di.DIContainer.DISABLE_SERVER_VALIDATION
import de.gematik.zeta.client.ui.common.mvi.MviState
import de.gematik.zeta.sdk.authentication.AuthMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public sealed interface SettingsState : MviState {
    public data class Result(
        val disableServerValidation: Boolean,
        val pemFilePath: String? = null,
        val authMode: AuthMode? = null,
    ) : SettingsState
}

@OptIn(ExperimentalReactiveStateApi::class)
public class SettingsViewModel(
    scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
) : ReactiveViewModel(scope) {
    private val _state = MutableStateFlow<SettingsState>(
        SettingsState.Result(disableServerValidation = DISABLE_SERVER_VALIDATION),
    )
    public val state: StateFlow<SettingsState> = _state.asStateFlow()

    public fun loadSettings() {
        scope.launch {
            val disabled = settingsRepository.getDisableServerValidation()
            val pemPath = settingsRepository.getPemFilePath()
            val authMode = settingsRepository.getAuthMode()
            _state.value = SettingsState.Result(disableServerValidation = disabled, pemFilePath = pemPath, authMode = authMode)
        }
    }

    public fun setDisableServerValidation(disabled: Boolean) {
        val current = _state.value as? SettingsState.Result ?: return
        _state.value = current.copy(disableServerValidation = disabled)
        scope.launch {
            settingsRepository.setDisableServerValidation(disabled)
        }
    }

    public fun setPemFile(path: String?) {
        val current = _state.value as? SettingsState.Result ?: return
        _state.value = current.copy(pemFilePath = path)
        scope.launch {
            settingsRepository.setPemFilePath(path)
        }
    }

    public fun setAuthMode(mode: AuthMode) {
        launch {
            DIContainer.settingsRepository.setAuthMode(mode)
            loadSettings()
        }
    }
}
