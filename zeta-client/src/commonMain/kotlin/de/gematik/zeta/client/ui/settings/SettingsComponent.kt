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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.ui.utils.buildViewModel
import de.gematik.zeta.sdk.authentication.AuthMode

@OptIn(ExperimentalReactiveStateApi::class)
@Composable
public fun SettingsComponent() {
    val viewModel by buildViewModel {
        SettingsViewModel(scope, DIContainer.settingsRepository)
    }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadSettings()
    }

    when (val s = state) {
        is SettingsState.Result -> SettingsForm(s, viewModel)
    }
}

@Composable
private fun SettingsForm(
    state: SettingsState.Result,
    viewModel: SettingsViewModel,
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Disable TLS validation")
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = state.disableServerValidation,
                onCheckedChange = viewModel::setDisableServerValidation,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(state.pemFilePath ?: "No PEM file selected")
            Spacer(modifier = Modifier.width(8.dp))

            val launchPicker = rememberPemFilePicker { path -> viewModel.setPemFile(path) }
            Button(onClick = launchPicker) {
                Text("Select PEM file")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Token provider", modifier = Modifier.padding(top = 16.dp))
            AuthMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.authMode == mode,
                        onClick = { viewModel.setAuthMode(mode) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(mode.name)
                }
            }
        }
    }
}
