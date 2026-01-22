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

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import com.russhwolf.settings.PreferencesSettings
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.AesGcmCipher
import java.util.prefs.Preferences

actual fun provideSdkStorage(aesB64Key: String): SdkStorage {
    val preferences = Preferences.userRoot()
    val base = PreferencesSettings(preferences)

    val secureSettings = EncryptedSettings(
        delegate = base,
        cipher = AesGcmCipher(),
        cipherB64Key = aesB64Key,
    )

    val secretStore: SecretStore? = createOsSecretStore(service = "de.gematik.zeta.sdk")
    return SecureSdkStorage(settings = secureSettings, secrets = secretStore)
}

fun createOsSecretStore(service: String = "de.gematik.zeta.sdk"): SecretStore? {
    return try {
        val keyring = Keyring.create()
        Log.i { "Using native keyring: ${keyring.keyringStorageType}" }
        KeyringSecretStore(service, keyring)
    } catch (ex: Exception) {
        Log.e(ex) { "Failed to initialize keyring" }
        null
    }
}

class KeyringSecretStore(private val service: String, private val keyring: Keyring) : SecretStore {
    override fun put(name: String, value: String) {
        try {
            keyring.setPassword(service, name, value)
        } catch (ex: Exception) {
            Log.e(ex) { "Failed to store secret: $name: ${ex.message}" }
        }
    }

    override fun get(name: String): String? {
        return try {
            val secret = keyring.getPassword(service, name)
            Log.d { "Retrieved secret found for: $name" }
            secret
        } catch (e: PasswordAccessException) {
            Log.e { "No secret found for: $name" }
            null
        } catch (ex: Exception) {
            Log.e(ex) { "Failed to get secret: $name" }
            null
        }
    }

    override fun remove(name: String) {
        return try {
            keyring.deletePassword(service, name)
            Log.d { "Retrieved secret found for: $name" }
        } catch (e: PasswordAccessException) {
            Log.e { "No secret found or already deleted for: $name" }
        } catch (ex: Exception) {
            Log.e(ex) { "Failed to delete secret: $name" }
        }
    }

    override fun clearNamespace() {
        TODO("Not yet implemented")
    }
}
