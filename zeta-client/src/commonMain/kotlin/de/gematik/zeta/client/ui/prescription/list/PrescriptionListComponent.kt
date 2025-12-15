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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ensody.reactivestate.ExperimentalReactiveStateApi
import de.gematik.zeta.client.model.PrescriptionModel
import de.gematik.zeta.client.ui.common.ErrorMessage
import de.gematik.zeta.client.ui.common.LoadingIndicator
import de.gematik.zeta.client.ui.common.mvi.MviState
import de.gematik.zeta.client.ui.prescription.add.AddPrescriptionComponent
import de.gematik.zeta.client.ui.prescription.edit.EditPrescriptionComponent
import de.gematik.zeta.client.ui.utils.buildViewModel

@OptIn(ExperimentalReactiveStateApi::class)
@Composable
public fun PrescriptionListComponent() {
    val viewModel by buildViewModel {
        PrescriptionListViewModel(scope)
    }
    val state by viewModel.state.collectAsState()

    var showAddPrescription by remember { mutableStateOf(false) }
    var showEditPrescription by remember { mutableStateOf(false) }
    var editableModelId by remember { mutableStateOf(-1L) }

    LaunchedEffect(Unit) {
        viewModel.loadPrescriptionList()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            Button(onClick = viewModel::loadPrescriptionList) {
                Text("Load")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { showAddPrescription = !showAddPrescription },
            ) {
                Text("Add")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = viewModel::forgetAuthorization) {
                Text("Forget")
            }
        }
        when (state) {
            is PrescriptionListState.Result -> PrescriptionList(
                (state as PrescriptionListState.Result).result,
                {
                    editableModelId = it.id ?: -1
                    showEditPrescription = true
                },
                viewModel::deletePrescription,
            )

            is MviState.Loading -> LoadingIndicator()

            is MviState.Error -> ErrorMessage((state as MviState.Error).error)
        }
    }

    if (showAddPrescription) {
        AddPrescriptionComponent(
            onDismiss = {
                showAddPrescription = false
            },
            onAdded = {
                showAddPrescription = false
                viewModel.loadPrescriptionList()
            },
        )
    }

    if (showEditPrescription) {
        EditPrescriptionComponent(
            modelId = editableModelId,
            onDismiss = {
                showEditPrescription = false
            },
            onSaved = {
                showEditPrescription = false
                viewModel.loadPrescriptionList()
            },
        )
    }
}

@Composable
public fun PrescriptionList(
    items: List<PrescriptionModel>,
    onEdit: (PrescriptionModel) -> Unit,
    onDelete: (PrescriptionModel) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.horizontalScroll(rememberScrollState()).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        items(items) { item ->
            PrescriptionListItem(item, onEdit, onDelete)
        }
    }
}

@Composable
public fun PrescriptionListItem(
    item: PrescriptionModel,
    onEdit: (PrescriptionModel) -> Unit,
    onDelete: (PrescriptionModel) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .height(50.dp)
            .fillMaxWidth(),
    ) {
        Text(
            modifier = Modifier.width(50.dp),
            text = item.id?.toString().orEmpty(),
        )
        Text(
            modifier = Modifier.width(200.dp),
            text = item.prescriptionId.orEmpty(),
        )
        Text(
            modifier = Modifier.width(150.dp),
            text = item.patientId.orEmpty(),
        )
        Text(
            modifier = Modifier.width(150.dp),
            text = item.practitionerId.orEmpty(),
        )
        Text(
            modifier = Modifier.width(150.dp),
            text = item.medicationName.orEmpty(),
        )
        Text(
            modifier = Modifier.width(100.dp),
            text = item.dosage.orEmpty(),
        )
        Text(
            modifier = Modifier.width(250.dp),
            text = item.issuedAt.orEmpty(),
        )
        Text(
            modifier = Modifier.width(250.dp),
            text = item.expiresAt.orEmpty(),
        )
        Text(
            modifier = Modifier.width(100.dp),
            text = item.status.orEmpty(),
        )
        Spacer(modifier = Modifier.width(16.dp))
        ListItemAction("\u270F") { onEdit(item) }
        ListItemAction("\uD83D\uDDD1") { onDelete(item) }
    }
}

@Composable
public fun ListItemAction(icon: String, onClick: () -> Unit = {}) {
    IconButton(onClick = onClick) {
        Text(
            text = icon,
            fontSize = 24.sp,
        )
    }
}
