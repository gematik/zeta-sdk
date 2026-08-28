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

package de.gematik.zeta.sdk.storage

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.RegistrySettings
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl

@OptIn(ExperimentalSettingsImplementation::class)
actual fun provideSdkStorage(config: StorageConfig.Default): SdkStorage {
    val base = RegistrySettings("SOFTWARE\\de.gematik.zeta.sdk")
    val chunkedBase = ChunkedSettings(base)
    return SecureSdkStorage(
        settings = EncryptedSettings(chunkedBase, AesGcmCipherImpl(), config.aesB64Key),
        secrets = null,
        namespace = config.namespace,
    )
}
