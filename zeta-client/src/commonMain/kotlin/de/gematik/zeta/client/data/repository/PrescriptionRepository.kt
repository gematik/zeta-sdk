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

package de.gematik.zeta.client.data.repository

import de.gematik.zeta.client.data.service.PrescriptionService
import de.gematik.zeta.client.model.PrescriptionModel

public interface PrescriptionRepository {
    public suspend fun prescriptionList(): List<PrescriptionModel>
    public suspend fun prescription(id: Long): PrescriptionModel
    public suspend fun addPrescription(model: PrescriptionModel)
    public suspend fun updatePrescription(id: Long, model: PrescriptionModel)
    public suspend fun deletePrescription(id: Long)
}

public class PrescriptionRepositoryImpl(
    private val prescriptionService: PrescriptionService,
) : PrescriptionRepository {

    override suspend fun prescriptionList(): List<PrescriptionModel> {
        return prescriptionService.prescriptionList()
    }

    override suspend fun prescription(id: Long): PrescriptionModel {
        return prescriptionService.prescription(id)
    }

    override suspend fun addPrescription(model: PrescriptionModel) {
        return prescriptionService.addPrescription(model)
    }

    override suspend fun updatePrescription(id: Long, model: PrescriptionModel) {
        return prescriptionService.putPrescription(id, model)
    }

    override suspend fun deletePrescription(id: Long) {
        return prescriptionService.deletePrescription(id)
    }
}
