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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.di.DIContainer.ENVIRONMENTS
import de.gematik.zeta.client.ui.utils.buildViewModel

@OptIn(ExperimentalReactiveStateApi::class)
@Composable
public fun EnvToggleComponent() {
    val viewModel by buildViewModel {
        EnvToggleViewModel(scope)
    }

    val state by viewModel.state.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false },
    ) {
        ENVIRONMENTS.forEach { env ->
            EnvToggleDropdownItem(env) {
                showMenu = false
                viewModel.toggleEnvironment(env)
            }
        }
    }

    Box(
        modifier = Modifier.padding(8.dp),
    ) {
        when (state) {
            is EnvToggleState.Environment -> EnvToggleField(
                envUrl = (state as EnvToggleState.Environment).envUrl,
                onClick = { showMenu = true },
            )
        }
    }
}

@Composable
public fun EnvToggleDropdownItem(
    envUrl: String,
    onClick: (String) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(envUrl) },
        onClick = { onClick(envUrl) },
    )
}

@Composable
public fun EnvToggleField(
    envUrl: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
    ) {
        Text(envUrl)
    }
}
