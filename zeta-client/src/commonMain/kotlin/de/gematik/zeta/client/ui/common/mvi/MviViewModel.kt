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

package de.gematik.zeta.client.ui.common.mvi

import com.ensody.reactivestate.ExperimentalReactiveStateApi
import com.ensody.reactivestate.ReactiveViewModel
import de.gematik.zeta.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalReactiveStateApi::class)
public abstract class MviViewModel<S : MviState>(
    scope: CoroutineScope,
    initialState: S,
) : ReactiveViewModel(scope) {

    internal val state: MutableStateFlow<MviState> = MutableStateFlow(initialState)

    init {
        loading.onEach { onLoading(it) }.launchIn(scope)
    }

    public open fun onLoading(loading: Int) {
        if (loading > 0) { state.update { MviState.Loading } }
    }

    override fun onError(error: Throwable) {
        Log.e(error) { error.message.orEmpty() }
        state.update { MviState.Error(error.message.orEmpty()) }
    }
}

public interface MviState {
    public object Loading : MviState
    public data class Error(val error: String) : MviState
}
