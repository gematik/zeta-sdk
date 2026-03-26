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

import com.russhwolf.settings.Settings
import de.gematik.zeta.sdk.crypto.AesGcmCipher
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureSdkStorageTest {
    @Test
    fun put_storesInSecrets_sensitiveKeyWithSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val fakeSecrets = FakeSecretStore()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = fakeSecrets)

        // Act
        sut.put("access_token", "token_value")

        // Assert
        assertEquals("token_value", fakeSecrets.store["access_token"])
        assertFalse(fakeSettings.store.containsKey("access_token"))
    }

    @Test
    fun put_storesInSettings_nonSensitiveKeyWithSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val fakeSecrets = FakeSecretStore()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = fakeSecrets)

        // Act
        sut.put("user_name", "john")

        // Assert
        assertTrue(fakeSettings.store.containsKey("user_name"))
        assertFalse(fakeSecrets.store.containsKey("user_name"))
    }

    @Test
    fun put_storesInSettings_sensitiveKeyWithoutSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = null)

        // Act
        sut.put("access_token", "token_value")

        // Assert
        assertTrue(fakeSettings.store.containsKey("access_token"))
    }

    @Test
    fun put_storesInSettings_nonSensitiveKeyWithoutSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = null)

        // Act
        sut.put("user_name", "john")

        // Assert
        assertTrue(fakeSettings.store.containsKey("user_name"))
    }

    @Test
    fun get_returnsFromSecrets_sensitiveKeyWithSecrets() = runTest {
        // Arrange
        val fakeSecrets = FakeSecretStore()
        fakeSecrets.store["access_token"] = "secret_value"
        val (sut, _) = buildSut(secrets = fakeSecrets)

        // Act
        val result = sut.get("access_token")

        // Assert
        assertEquals("secret_value", result)
    }

    @Test
    fun get_returnsFromSettings_nonSensitiveKeyWithSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val (sut, _) = buildSut(fakeSettings = fakeSettings)
        sut.put("user_name", "john")

        // Act
        val result = sut.get("user_name")

        // Assert
        assertEquals("john", result)
    }

    @Test
    fun get_returnsFromSettings_sensitiveKeyWithoutSecrets() = runTest {
        // Arrange
        val (sut, _) = buildSut(secrets = null)
        sut.put("access_token", "token_value")

        // Act
        val result = sut.get("access_token")

        // Assert
        assertEquals("token_value", result)
    }

    @Test
    fun get_returnsEmptyString_missingKeyWithoutSecrets() = runTest {
        // Arrange
        val (sut, _) = buildSut(secrets = null)

        // Act
        val result = sut.get("nonexistent_key")

        // Assert
        assertEquals("", result)
    }

    @Test
    fun get_returnsNull_missingKeyInSecrets() = runTest {
        // Arrange
        val fakeSecrets = FakeSecretStore()
        val (sut, _) = buildSut(secrets = fakeSecrets)

        // Act
        val result = sut.get("access_token")

        // Assert
        assertNull(result)
    }

    @Test
    fun remove_removesFromSecrets_sensitiveKeyWithSecrets() = runTest {
        // Arrange
        val fakeSecrets = FakeSecretStore()
        fakeSecrets.store["access_token"] = "token_value"
        val (sut, _) = buildSut(secrets = fakeSecrets)

        // Act
        sut.remove("access_token")

        // Assert
        assertFalse(fakeSecrets.store.containsKey("access_token"))
    }

    @Test
    fun remove_removesFromSettings_nonSensitiveKeyWithSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val (sut, _) = buildSut(fakeSettings = fakeSettings)
        sut.put("user_name", "john")

        // Act
        sut.remove("user_name")

        // Assert
        assertFalse(fakeSettings.store.containsKey("user_name"))
    }

    @Test
    fun remove_removesFromSettings_sensitiveKeyWithoutSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = null)
        sut.put("access_token", "token_value")

        // Act
        sut.remove("access_token")

        // Assert
        assertFalse(fakeSettings.store.containsKey("access_token"))
    }

    @Test
    fun remove_removesFromSettings_nonSensitiveKeyWithoutSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = null)
        sut.put("user_name", "john")

        // Act
        sut.remove("user_name")

        // Assert
        assertFalse(fakeSettings.store.containsKey("user_name"))
    }

    @Test
    fun clear_clearsSettingsAndSecrets_bothPresent() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val fakeSecrets = FakeSecretStore()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = fakeSecrets)
        sut.put("user_name", "john")
        fakeSecrets.store["access_token"] = "token_value"

        // Act
        sut.clear()

        // Assert
        assertTrue(fakeSettings.store.isEmpty())
        assertTrue(fakeSecrets.store.isEmpty())
        assertTrue(fakeSecrets.clearNamespaceCalled)
    }

    @Test
    fun clear_clearsOnlySettings_secretsNull() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val (sut, _) = buildSut(fakeSettings = fakeSettings, secrets = null)
        sut.put("key1", "value1")
        sut.put("key2", "value2")

        // Act
        sut.clear()

        // Assert
        assertTrue(fakeSettings.store.isEmpty())
    }

    @Test
    fun defaultSensitiveKeys_returnsTrue_endsWithToken() {
        // Arrange
        val keys = listOf("access_token", "refresh_token", "id_token", "ACCESS_TOKEN", "Bearer_Token")

        // Act & Assert
        keys.forEach { assertTrue(SecureSdkStorage.defaultSensitiveKeys(it)) }
    }

    @Test
    fun defaultSensitiveKeys_returnsTrue_containsPrivateKey() {
        // Arrange
        val keys = listOf("private_key", "rsa_private_key_123", "PRIVATE_KEY", "myPrivate_Key")

        // Act & Assert
        keys.forEach { assertTrue(SecureSdkStorage.defaultSensitiveKeys(it)) }
    }

    @Test
    fun defaultSensitiveKeys_returnsTrue_containsPublicKey() {
        // Arrange
        val keys = listOf("public_key", "rsa_public_key_123", "PUBLIC_KEY", "myPublic_Key")

        // Act & Assert
        keys.forEach { assertTrue(SecureSdkStorage.defaultSensitiveKeys(it)) }
    }

    @Test
    fun defaultSensitiveKeys_returnsFalse_nonSensitiveKeys() {
        // Arrange
        val keys = listOf("user_name", "base_url", "client_id", "token_prefix", "tokenized", "")

        // Act & Assert
        keys.forEach { assertFalse(SecureSdkStorage.defaultSensitiveKeys(it)) }
    }

    @Test
    fun defaultSensitiveKeys_returnsFalse_tokenNotAtEnd() {
        // Arrange
        val keys = listOf("token_expiry", "token_type")

        // Act & Assert
        keys.forEach { assertFalse(SecureSdkStorage.defaultSensitiveKeys(it)) }
    }

    @Test
    fun put_usesCustomSensitiveFunction_storesInSecrets() = runTest {
        // Arrange
        val fakeSettings = FakeSettings()
        val fakeSecrets = FakeSecretStore()
        val (sut, _) = buildSut(
            fakeSettings = fakeSettings,
            secrets = fakeSecrets,
            isSensitive = { key -> key.startsWith("secret_") },
        )

        // Act
        sut.put("secret_data", "value")
        sut.put("public_data", "value2")

        // Assert
        assertTrue(fakeSecrets.store.containsKey("secret_data"))
        assertFalse(fakeSecrets.store.containsKey("public_data"))
        assertTrue(fakeSettings.store.containsKey("public_data"))
    }

    private class FakeSettings : Settings {
        val store = mutableMapOf<String, String>()

        override val keys: Set<String> get() = store.keys
        override val size: Int get() = store.size
        override fun clear() { store.clear() }
        override fun remove(key: String) { store.remove(key) }
        override fun hasKey(key: String): Boolean = store.containsKey(key)

        override fun putString(key: String, value: String) { store[key] = value }
        override fun getString(key: String, defaultValue: String): String = store[key] ?: defaultValue
        override fun getStringOrNull(key: String): String? = store[key]

        override fun putInt(key: String, value: Int) {}
        override fun getInt(key: String, defaultValue: Int): Int = defaultValue
        override fun getIntOrNull(key: String): Int? = null

        override fun putLong(key: String, value: Long) {}
        override fun getLong(key: String, defaultValue: Long): Long = defaultValue
        override fun getLongOrNull(key: String): Long? = null

        override fun putFloat(key: String, value: Float) {}
        override fun getFloat(key: String, defaultValue: Float): Float = defaultValue
        override fun getFloatOrNull(key: String): Float? = null

        override fun putDouble(key: String, value: Double) {}
        override fun getDouble(key: String, defaultValue: Double): Double = defaultValue
        override fun getDoubleOrNull(key: String): Double? = null

        override fun putBoolean(key: String, value: Boolean) {}
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override fun getBooleanOrNull(key: String): Boolean? = null
    }

    private class PassthroughAesGcmCipher : AesGcmCipher {
        override fun encrypt(aesKey: ByteArray, plainText: ByteArray, iv: ByteArray?, aad: ByteArray?): ByteArray = plainText
        override fun decrypt(aesKey: ByteArray, cipherText: ByteArray, iv: ByteArray?, aad: ByteArray?): ByteArray = cipherText
    }

    private class FakeSecretStore : SecretStore {
        val store = mutableMapOf<String, String>()
        var clearNamespaceCalled = false

        override fun put(name: String, value: String) { store[name] = value }
        override fun get(name: String): String? = store[name]
        override fun remove(name: String) { store.remove(name) }
        override fun clearNamespace() {
            clearNamespaceCalled = true
            store.clear()
        }
    }

    private val aesKey = ByteArray(32) { 0x42 }
    private val aesB64Key = Base64.encode(aesKey)

    private fun buildEncryptedSettings(fakeSettings: FakeSettings = FakeSettings()): EncryptedSettings =
        EncryptedSettings(
            delegate = fakeSettings,
            cipher = PassthroughAesGcmCipher(),
            cipherB64Key = aesB64Key,
        )

    private fun buildSut(
        fakeSettings: FakeSettings = FakeSettings(),
        secrets: FakeSecretStore? = FakeSecretStore(),
        isSensitive: (String) -> Boolean = SecureSdkStorage.Companion::defaultSensitiveKeys,
    ): Pair<SecureSdkStorage, FakeSettings> {
        val encryptedSettings = buildEncryptedSettings(fakeSettings)
        return SecureSdkStorage(encryptedSettings, secrets, isSensitive) to fakeSettings
    }
}
