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

package de.gematik.zeta.client.di

import de.gematik.zeta.client.config.getConfig
import de.gematik.zeta.client.data.repository.PrescriptionRepository
import de.gematik.zeta.client.data.repository.PrescriptionRepositoryImpl
import de.gematik.zeta.client.data.service.PrescriptionService
import de.gematik.zeta.client.data.service.PrescriptionServiceImpl
import de.gematik.zeta.client.data.service.fake.FakePrescriptionService
import de.gematik.zeta.client.data.service.http.HttpClientProvider
import de.gematik.zeta.client.data.service.http.HttpClientProviderImpl
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider

private const val USE_FAKE_SERVICES = false
internal const val DEBUG_LOGGING = true

public const val POPP_TOKEN_HEADER_NAME: String = "PoPP"

public object DIContainer {
    public val httpClientProvider: HttpClientProvider = HttpClientProviderImpl()

    public val prescriptionService: PrescriptionService =
        if (USE_FAKE_SERVICES) {
            FakePrescriptionService()
        } else {
            PrescriptionServiceImpl()
        }

    public val prescriptionRepository: PrescriptionRepository =
        PrescriptionRepositoryImpl(prescriptionService)

    init {
        if (DEBUG_LOGGING) {
            Log.initDebugLogger()
        }
    }
    public val ENVIRONMENTS: List<String> = getUrlEnvironments()

    public val SMB_KEYSTORE_CREDENTIALS: SmbTokenProvider.Credentials = SmbTokenProvider.Credentials(
        getConfig("SMB_KEYSTORE_FILE") ?: "",
        getConfig("SMB_KEYSTORE_ALIAS") ?: "",
        getConfig("SMB_KEYSTORE_PASSWORD") ?: "",
    )

    public val SMCB_CONNECTOR_CONFIG: SmcbTokenProvider.ConnectorConfig = SmcbTokenProvider.ConnectorConfig(
        getConfig("SMCB_BASE_URL") ?: "",
        getConfig("SMCB_MANDANT_ID") ?: "",
        getConfig("SMCB_CLIENT_SYSTEM_ID") ?: "",
        getConfig("SMCB_WORKSPACE_ID") ?: "",
        getConfig("SMCB_USER_ID") ?: "",
        getConfig("SMCB_CARD_HANDLE") ?: "",
    )

    public val DISABLE_SERVER_VALIDATION: Boolean = "true".contentEquals((getConfig("DISABLE_SERVER_VALIDATION") ?: "").lowercase())

    public val POPP_TOKEN: String? = getConfig("POPP_TOKEN")

    public val ASL_PROD: Boolean = "true".contentEquals((getConfig("ASL_PROD") ?: "true").lowercase())

    private fun getUrlEnvironments(): List<String> {
        return getConfig("ENVIRONMENTS")
            ?.trim()
            ?.split(" ")
            ?.filter { it.isNotBlank() } ?: emptyList()
    }
}
