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

package de.gematik.zeta.client.ui.prescription.list

import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.data.repository.PrescriptionRepository
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.model.PrescriptionModel
import de.gematik.zeta.client.ui.common.mvi.MviState
import de.gematik.zeta.client.ui.common.mvi.MviViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalReactiveStateApi::class)
public class PrescriptionListViewModel(
    scope: CoroutineScope,
    private val repository: PrescriptionRepository = DIContainer.prescriptionRepository,
) : MviViewModel<PrescriptionListState>(
    scope,
    initialState = PrescriptionListState.Result(emptyList()),
) {

    internal fun loadPrescriptionList() = launch {
        val list = repository.prescriptionList()
        state.update { PrescriptionListState.Result(list) }
    }

    internal fun deletePrescription(model: PrescriptionModel) = launch {
        repository.deletePrescription(model.id ?: -1)
        val list = repository.prescriptionList()
        state.update { PrescriptionListState.Result(list) }
    }
}

public sealed class PrescriptionListState : MviState {
    public data class Result(val result: List<PrescriptionModel>) : PrescriptionListState()
}
