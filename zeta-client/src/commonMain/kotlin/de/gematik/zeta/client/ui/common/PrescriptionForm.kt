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

package de.gematik.zeta.client.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.gematik.zeta.client.model.PrescriptionModel

@Composable
public fun PrescriptionForm(
    model: PrescriptionModel,
    onChanged: (PrescriptionModel) -> Unit,
) {
    Column(
        modifier = Modifier.padding(8.dp),
    ) {
        PrescriptionField("id", model.id?.toString().orEmpty()) {
            onChanged(model.copy(id = it.toLongOrNull()))
        }
        PrescriptionField("prescriptionId", model.prescriptionId.orEmpty()) {
            onChanged(model.copy(prescriptionId = it))
        }
        PrescriptionField("patientId", model.patientId.orEmpty()) {
            onChanged(model.copy(patientId = it))
        }
        PrescriptionField("practitionerId", model.practitionerId.orEmpty()) {
            onChanged(model.copy(practitionerId = it))
        }
        PrescriptionField("medicationName", model.medicationName.orEmpty()) {
            onChanged(model.copy(medicationName = it))
        }
        PrescriptionField("dosage", model.dosage.orEmpty()) {
            onChanged(model.copy(dosage = it))
        }
        PrescriptionField("issuedAt", model.issuedAt.orEmpty()) {
            onChanged(model.copy(issuedAt = it))
        }
        PrescriptionField("expiresAt", model.expiresAt.orEmpty()) {
            onChanged(model.copy(expiresAt = it))
        }
        PrescriptionField("status", model.status.orEmpty()) {
            onChanged(model.copy(status = it))
        }
    }
}

@Composable
public fun PrescriptionField(name: String, value: String, onChanged: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value,
            onValueChange = onChanged,
            label = { Text(name) },
            modifier = Modifier.height(60.dp),
        )
    }
}
