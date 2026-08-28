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

import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.client.di.DIContainer.AUTH_MODE
import de.gematik.zeta.client.di.DIContainer.DISABLE_SERVER_VALIDATION
import de.gematik.zeta.sdk.authentication.AuthMode

public interface SettingsRepository {
    public suspend fun getDisableServerValidation(): Boolean
    public suspend fun setDisableServerValidation(disabled: Boolean)
    public suspend fun getPemFilePath(): String?
    public suspend fun setPemFilePath(path: String?)
    public suspend fun setAuthMode(mode: AuthMode)
    public suspend fun getAuthMode(): AuthMode
}

public class SettingsRepositoryImpl : SettingsRepository {
    private var tlsValidationDisabled: Boolean = DISABLE_SERVER_VALIDATION
    private var pemFilePath: String? = null
    private var authMode: AuthMode = AUTH_MODE

    override suspend fun getDisableServerValidation(): Boolean = tlsValidationDisabled

    override suspend fun setDisableServerValidation(disabled: Boolean) {
        tlsValidationDisabled = disabled
        DIContainer.httpClientProvider.updateTlsValidation(disabled)
    }

    override suspend fun getPemFilePath(): String? = pemFilePath

    override suspend fun setPemFilePath(path: String?) {
        pemFilePath = path
        DIContainer.httpClientProvider.updateTrustedCa(path)
    }

    override suspend fun setAuthMode(mode: AuthMode) {
        authMode = mode
        DIContainer.httpClientProvider.setAuthMode(mode)
    }

    override suspend fun getAuthMode(): AuthMode = authMode
}
