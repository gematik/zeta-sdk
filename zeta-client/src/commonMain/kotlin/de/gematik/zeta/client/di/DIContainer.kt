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
import de.gematik.zeta.client.data.repository.SettingsRepository
import de.gematik.zeta.client.data.repository.SettingsRepositoryImpl
import de.gematik.zeta.client.data.service.PrescriptionService
import de.gematik.zeta.client.data.service.PrescriptionServiceImpl
import de.gematik.zeta.client.data.service.fake.FakePrescriptionService
import de.gematik.zeta.client.data.service.http.HttpClientProvider
import de.gematik.zeta.client.data.service.http.HttpClientProviderImpl
import de.gematik.zeta.client.ui.otp.GuiOtpCallback
import de.gematik.zeta.logging.Log
import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.authentication.AuthMode
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider

private const val USE_FAKE_SERVICES = false
internal const val DEBUG_LOGGING = true

public const val POPP_TOKEN_HEADER_NAME: String = "PoPP"

public object DIContainer {
    public const val CUSTOM_SMCB_ENABLED: Boolean = false
    public val DISABLE_SERVER_VALIDATION: Boolean = "true".contentEquals((getConfig("DISABLE_SERVER_VALIDATION") ?: "").lowercase())
    public val AUTH_MODE: AuthMode =
        when (getConfig("AUTH_MODE")?.lowercase()) {
            "oidc" -> AuthMode.OIDC
            "smb" -> AuthMode.SMB
            else -> defaultAuthModeForPlatform()
        }

    private fun defaultAuthModeForPlatform(): AuthMode = when (platform()) {
        Platform.Android, Platform.IOS -> AuthMode.OIDC
        else -> AuthMode.SMB
    }

    public val otpCallback: GuiOtpCallback = GuiOtpCallback()
    public val httpClientProvider: HttpClientProvider = HttpClientProviderImpl(otpCallback)
    public val settingsRepository: SettingsRepository = SettingsRepositoryImpl()

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

    public val PUSH_GATEWAY_URL: String = getConfig("PUSH_GATEWAY_URL") ?: ""
    public val OIDC_BASE_URI: String = getConfig("OIDC_BASE_URI") ?: ""
    public val OIDC_IDP_ISS: String = getConfig("OIDC_IDP_ISS") ?: ""
    public val OIDC_IDP_ALIAS: String = getConfig("OIDC_IDP_ALIAS") ?: ""
    public val STORAGE_AES_KEY: String = getConfig("STORAGE_AES_KEY") ?: error("STORAGE_AES_KEY must be provided.")
    public val POPP_TOKEN: String? = getConfig("POPP_TOKEN")
    public val ASL_PROD: Boolean = "true".contentEquals((getConfig("ASL_PROD") ?: "true").lowercase())
    public val REQUIRED_OID: String? = getConfig("REQUIRED_ROLE_OID")
    private fun getUrlEnvironments(): List<String> {
        return getConfig("ENVIRONMENTS")
            ?.trim()
            ?.split(" ")
            ?.filter { it.isNotBlank() } ?: emptyList()
    }
}
