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

package de.gematik.zeta.client.ui.prescription.add

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.ui.common.ErrorMessage
import de.gematik.zeta.client.ui.common.FormDialog
import de.gematik.zeta.client.ui.common.LoadingIndicator
import de.gematik.zeta.client.ui.common.PrescriptionForm
import de.gematik.zeta.client.ui.common.mvi.MviState
import de.gematik.zeta.client.ui.utils.buildViewModel

@OptIn(ExperimentalReactiveStateApi::class)
@Composable
public fun AddPrescriptionComponent(
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val viewModel by buildViewModel {
        AddPrescriptionViewModel(scope)
    }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.clearForm()
    }

    FormDialog(
        onDismiss = {
            viewModel.clearForm()
            onDismiss()
        },
        onSave = viewModel::savePrescription,
    ) {
        Button(onClick = viewModel::fillForm) {
            Text("Fill Form")
        }
        when (state) {
            is AddPrescriptionState.FormUpdated -> PrescriptionForm(
                (state as AddPrescriptionState.FormUpdated).result,
                viewModel::updatePrescription,
            )
            is AddPrescriptionState.Added -> LaunchedEffect(Unit) {
                onAdded()
                viewModel.clearForm()
            }
            is MviState.Loading -> LoadingIndicator()
            is MviState.Error -> ErrorMessage((state as MviState.Error).error)
        }
    }
}
