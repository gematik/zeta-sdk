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

import com.russhwolf.settings.PreferencesSettings
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
    val os = (System.getProperty("os.name") ?: "").lowercase()
    return when {
        os.contains("mac") -> MacKeychainStore(service)

        os.contains("linux") -> null

        // TODO()
        os.contains("win") -> null

        // TODO()
        else -> error("Unsupported OS for SecretStore: $os")
    }
}

class MacKeychainStore(private val service: String) : SecretStore {
    override fun put(name: String, value: String) { keychainSet(service, name, value) }
    override fun get(name: String): String? = keychainGet(service, name)
    override fun remove(name: String) { runSecurity("delete-generic-password", "-s", service, "-a", name) }
    override fun clearNamespace() { TODO() }
}

private fun runSecurity(vararg args: String): Pair<Int, String> {
    val pb = ProcessBuilder(listOf("security") + args).redirectErrorStream(true)
    val p = pb.start(); val out = p.inputStream.bufferedReader().readText().trim(); val code = p.waitFor(); return code to out
}
private fun keychainGet(service: String, account: String): String? {
    val (code, out) = runSecurity("find-generic-password", "-s", service, "-a", account, "-w")
    return if (code == 0) out else null
}
private fun keychainSet(service: String, account: String, secret: String) {
    val (code, out) = runSecurity("add-generic-password", "-s", service, "-a", account, "-w", secret, "-U")
    require(code == 0) { "Keychain set failed ($service/$account): $out" }
}
