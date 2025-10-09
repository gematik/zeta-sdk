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

package de.gematik.zeta.client.ui.prescription.edit

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
public fun EditPrescriptionComponent(
    modelId: Long,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel by buildViewModel {
        EditPrescriptionViewModel(scope)
    }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(modelId) {
        viewModel.loadPrescription(modelId)
    }

    FormDialog(
        onDismiss = {
            viewModel.clearForm()
            onDismiss()
        },
        onSave = viewModel::savePrescription,
    ) {
        when (state) {
            is EditPrescriptionState.FormUpdated -> PrescriptionForm(
                model = (state as EditPrescriptionState.FormUpdated).result,
                onChanged = viewModel::updateForm,
            )
            is EditPrescriptionState.Saved -> LaunchedEffect(Unit) {
                onSaved()
                viewModel.clearForm()
            }

            is MviState.Loading -> LoadingIndicator()
            is MviState.Error -> ErrorMessage((state as MviState.Error).error)
        }
    }
}
