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
public class AddPrescriptionViewModel(
    scope: CoroutineScope,
    private val repository: PrescriptionRepository = DIContainer.prescriptionRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MviViewModel<AddPrescriptionState>(
    scope,
    initialState = AddPrescriptionState.FormUpdated(PrescriptionModel()),
) {

    private var model = PrescriptionModel()

    internal fun fillForm() {
        model = SAMPLE_MODEL.copy()
        state.update { AddPrescriptionState.FormUpdated(model) }
    }

    internal fun updatePrescription(updated: PrescriptionModel) {
        model = updated
        state.update { AddPrescriptionState.FormUpdated(model) }
    }

    internal fun savePrescription() = launch(ioDispatcher) {
        repository.addPrescription(model)
        model = PrescriptionModel()
        state.update { AddPrescriptionState.Added }
    }

    internal fun clearForm() {
        model = PrescriptionModel()
        state.update { AddPrescriptionState.FormUpdated(model) }
    }
}

public sealed class AddPrescriptionState : MviState {
    public object Added : AddPrescriptionState()
    public data class FormUpdated(val result: PrescriptionModel) : AddPrescriptionState()
}

private val SAMPLE_MODEL = PrescriptionModel(
    prescriptionId = "RX-2025-000123",
    patientId = "PAT-123456",
    practitionerId = "PRAC-98765",
    medicationName = "Ibuprofen 400 mg",
    dosage = "1",
    issuedAt = "2025-09-22T10:30:00Z",
    expiresAt = "2025-12-31T23:59:59Z",
    status = "CREATED",
)
