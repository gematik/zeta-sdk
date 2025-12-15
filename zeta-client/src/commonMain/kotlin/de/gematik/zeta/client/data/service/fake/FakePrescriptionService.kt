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

package de.gematik.zeta.client.data.service.fake

import de.gematik.zeta.client.data.service.PrescriptionService
import de.gematik.zeta.client.model.PrescriptionModel
import kotlinx.coroutines.delay

private const val FAKE_PRESCRIPTION_ID = "c504b7bd-d570-4460-a107-dcedaab791a9"

private const val FAKE_PATIENT_ID = "628b041a-6bf8-4147-89f8-eaca5bfeb867"

private const val FAKE_PRACTITIONER_ID = "64c5ca72-85bf-4c71-8319-ed2fccb2f755"

private const val FAKE_ISSUED_AT = "2024-12-10T00:30:00.000Z"

private const val FAKE_EXPIRES_AT = "2027-12-10T00:30:00.000Z"

private const val STATUS_ACTIVE = "active"

private const val ERROR_MODEL_ID_IS_NULL = "model.id is null"

public class FakePrescriptionService : PrescriptionService {

    private val items = mutableListOf(
        PrescriptionModel(
            id = 0,
            prescriptionId = FAKE_PRESCRIPTION_ID,
            patientId = FAKE_PATIENT_ID,
            practitionerId = FAKE_PRACTITIONER_ID,
            medicationName = "Aspirine",
            dosage = "81 mg",
            issuedAt = FAKE_ISSUED_AT,
            expiresAt = FAKE_EXPIRES_AT,
            status = STATUS_ACTIVE,
        ),
        PrescriptionModel(
            id = 1,
            prescriptionId = FAKE_PRESCRIPTION_ID,
            patientId = FAKE_PATIENT_ID,
            practitionerId = FAKE_PRACTITIONER_ID,
            medicationName = "Morphine",
            dosage = "10 mg",
            issuedAt = FAKE_ISSUED_AT,
            expiresAt = FAKE_EXPIRES_AT,
            status = STATUS_ACTIVE,
        ),
        PrescriptionModel(
            id = 2,
            prescriptionId = FAKE_PRESCRIPTION_ID,
            patientId = FAKE_PATIENT_ID,
            practitionerId = FAKE_PRACTITIONER_ID,
            medicationName = "Carvedilol",
            dosage = "25 mg",
            issuedAt = FAKE_ISSUED_AT,
            expiresAt = FAKE_EXPIRES_AT,
            status = STATUS_ACTIVE,
        ),
    )

    override suspend fun prescriptionList(): List<PrescriptionModel> {
        delay(1000)
        return items
    }

    override suspend fun prescription(id: Long): PrescriptionModel {
        delay(1000)
        return items.first { it.id == id }
    }

    override suspend fun addPrescription(model: PrescriptionModel) {
        delay(1000)
        requireNotNull(model.id) { ERROR_MODEL_ID_IS_NULL }
        val exist = items.any { it.id == model.id }
        require(!exist) { "item with model.id: ${model.id} already exist" }
        items.add(model)
    }

    override suspend fun putPrescription(id: Long, model: PrescriptionModel) {
        delay(1000)
        val index = items.indexOfFirst { it.id == id }
        require(index != -1) { "can't find item with id: $id" }
        val exist = items.any { it.id == model.id && id != model.id }
        require(!exist) { "item with model.id: ${model.id} already exist" }
        items[index] = model
    }

    override suspend fun deletePrescription(id: Long) {
        delay(1000)
        require(id != -1L) { "model.id is -1" }
        items.removeAll { it.id == id }
    }
}
