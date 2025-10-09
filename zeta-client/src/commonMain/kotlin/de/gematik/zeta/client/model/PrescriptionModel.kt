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

package de.gematik.zeta.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class PrescriptionModel(
    @SerialName("id") val id: Long? = null,
    @SerialName("prescriptionId") val prescriptionId: String? = null,
    @SerialName("patientId") val patientId: String? = null,
    @SerialName("practitionerId") val practitionerId: String? = null,
    @SerialName("medicationName") val medicationName: String? = null,
    @SerialName("dosage") val dosage: String? = null,
    @SerialName("issuedAt") val issuedAt: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
    @SerialName("status") val status: String? = null,
)
