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

import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.data.repository.PrescriptionRepository
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.model.PrescriptionModel
import de.gematik.zeta.client.ui.common.mvi.MviState
import de.gematik.zeta.client.ui.common.mvi.MviViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalReactiveStateApi::class)
public class EditPrescriptionViewModel(
    scope: CoroutineScope,
    private val repository: PrescriptionRepository = DIContainer.prescriptionRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MviViewModel<EditPrescriptionState>(
    scope,
    initialState = EditPrescriptionState.FormUpdated(PrescriptionModel()),
) {

    private var id = -1L
    private var model = PrescriptionModel()

    internal fun loadPrescription(modelId: Long?) = launch(ioDispatcher) {
        requireNotNull(id) { "model.id is null" }
        id = modelId ?: -1
        model = repository.prescription(id)
        state.update { EditPrescriptionState.FormUpdated(model) }
    }

    internal fun updateForm(updated: PrescriptionModel) {
        model = updated
        state.update { EditPrescriptionState.FormUpdated(model) }
    }

    internal fun savePrescription() = launch(ioDispatcher) {
        repository.updatePrescription(id, model)
        model = PrescriptionModel()
        state.update { EditPrescriptionState.Saved }
    }

    internal fun clearForm() {
        model = PrescriptionModel()
        state.update { EditPrescriptionState.FormUpdated(model) }
    }
}

public sealed class EditPrescriptionState : MviState {
    public object Saved : EditPrescriptionState()
    public data class FormUpdated(val result: PrescriptionModel) : EditPrescriptionState()
}
