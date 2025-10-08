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

package de.gematik.zeta.client.ui.environment.toggle

import de.gematik.zeta.client.data.service.http.HttpClientProvider
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.di.DIContainer.ENVIRONMENTS
import de.gematik.zeta.client.ui.common.mvi.MviState
import de.gematik.zeta.client.ui.common.mvi.MviViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update

public class EnvToggleViewModel(
    scope: CoroutineScope,
    private val httpClientProvider: HttpClientProvider = DIContainer.httpClientProvider,
    private val initialUrlProvider: () -> String? = { ENVIRONMENTS.firstOrNull() },
) : MviViewModel<EnvToggleState>(
    scope,
    initialState = EnvToggleState.Environment(initialUrlProvider() ?: ""),
) {

    init {
        val url = (state.value as EnvToggleState.Environment).envUrl
        if (url.isNotBlank()) {
            httpClientProvider.setupEnvUrl(url)
        }
    }

    public fun toggleEnvironment(url: String): Job = launch {
        httpClientProvider.setupEnvUrl(url)
        state.update { EnvToggleState.Environment(url) }
    }
}

public sealed class EnvToggleState : MviState {
    public data class Environment(val envUrl: String) : EnvToggleState()
}
