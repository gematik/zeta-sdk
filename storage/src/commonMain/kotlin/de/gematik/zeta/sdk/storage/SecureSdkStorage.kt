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

class SecureSdkStorage(
    private val settings: EncryptedSettings,
    private val secrets: SecretStore?,
    private val isSensitive: (String) -> Boolean = ::defaultSensitiveKeys,
) : SdkStorage {
    override suspend fun put(key: String, value: String) {
        if (secrets != null && isSensitive(key)) secrets.put(key, value) else settings.putString(key, value)
    }
    override suspend fun get(key: String): String? =
        if (secrets != null && isSensitive(key)) secrets.get(key) else settings.getString(key, "")
    override suspend fun remove(key: String) {
        if (secrets != null && isSensitive(key)) secrets.remove(key) else settings.remove(key)
    }
    override suspend fun clear() {
        secrets?.clearNamespace()
        settings.clear()
    }
    companion object Companion {
        fun defaultSensitiveKeys(key: String): Boolean {
            val k = key.lowercase()
            return k.endsWith("_token") || k.contains("private_key") || k.contains("public_key")
        }
    }
}
