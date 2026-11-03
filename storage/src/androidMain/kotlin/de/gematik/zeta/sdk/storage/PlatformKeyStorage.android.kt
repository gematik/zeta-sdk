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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.russhwolf.settings.SharedPreferencesSettings
import de.gematik.zeta.android.baseContext
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import secureRandom
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

private const val STORAGE_PREFS_NAME = "de.gematik.zeta.sdk.storage"
private const val KEY_MATERIAL_PREFS_NAME = "de.gematik.zeta.sdk.storage.key_material"
private const val WRAPPED_DEK_ENTRY = "wrapped_dek"
private const val DEK_SIZE_BYTES = 32

/**
 * Android ignores [StorageConfig.Default.aesB64Key]: the value protecting storage here is a
 * randomly generated DEK, itself wrapped by a non-exportable Android Keystore key (hardware-backed
 * where the device supports it) rather than an app-supplied secret. The push notification concept
 * classifies the key material this storage exists to protect (`A_29968`/`A_29970` ISS-derived
 * secrets) as "sehr hoch" (very high) protection need.
 */
actual fun provideSdkStorage(config: StorageConfig.Default): SdkStorage {
    val context = baseContext
    val dek = getOrCreateDek(context, AndroidKeystoreDekWrapper())

    val settings = SharedPreferencesSettings(context.getSharedPreferences(STORAGE_PREFS_NAME, Context.MODE_PRIVATE))
    val encryptedSettings = EncryptedSettings(
        delegate = ChunkedSettings(settings),
        cipher = AesGcmCipherImpl(),
        cipherB64Key = Base64.encode(dek),
    )
    return SecureSdkStorage(settings = encryptedSettings, secrets = null, namespace = config.namespace)
}

/**
 * Returns the storage-encryption DEK, generating and persisting one (wrapped by [wrapper]) on
 * first use. A DEK that fails to unwrap (its Keystore entry gone - e.g. rare OS-level Keystore
 * resets) is treated the same as no DEK at all: a fresh one is generated. Every value previously
 * encrypted under the lost DEK becomes unreadable, but that's already handled by
 * [EncryptedSettings] itself, which drops any value it fails to decrypt rather than throwing.
 */
internal fun getOrCreateDek(context: Context, wrapper: DekWrapper): ByteArray {
    val prefs = context.getSharedPreferences(KEY_MATERIAL_PREFS_NAME, Context.MODE_PRIVATE)
    val wrapped = prefs.getString(WRAPPED_DEK_ENTRY, null)?.let { Base64.decode(it) }
    val existing = wrapped?.let {
        runCatching { wrapper.unwrap(it) }
            .onFailure { Log.w(throwable = it) { "Could not unwrap the stored storage key; regenerating it" } }
            .getOrNull()
    }
    if (existing != null) return existing

    val dek = ByteArray(DEK_SIZE_BYTES).also { secureRandom(it) }
    prefs.edit().putString(WRAPPED_DEK_ENTRY, Base64.encode(wrapper.wrap(dek))).apply()
    return dek
}

internal interface DekWrapper {
    fun wrap(dek: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Wraps/unwraps the DEK with a non-exportable AES key kept in the Android Keystore (envelope
 * encryption), so the rest of the storage pipeline ([EncryptedSettings], [AesGcmCipherImpl]) is
 * untouched and only ever sees the raw DEK, never the Keystore key.
 *
 * `setUserAuthenticationRequired(false)`: this must be usable from a background process (e.g. FCM
 * push delivery) with no user present to unlock anything.
 *
 * Not covered by this module's unit tests: Robolectric has no real `AndroidKeyStore` provider
 * (`KeyStore.getInstance("AndroidKeyStore")` throws under it), so this class can only be verified
 * on a real device/emulator. [getOrCreateDek] carries the actual test coverage, against a fake
 * [DekWrapper].
 */
internal class AndroidKeystoreDekWrapper : DekWrapper {
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private val secretKey: SecretKey
        get() = (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: generateKey()

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(DEK_SIZE_BYTES * Byte.SIZE_BITS)
                // Operates in the background when push notifications arrive, without requiring a
                // fingerprint or PIN from the user.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    override fun wrap(dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.iv + cipher.doFinal(dek)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_LEN) { "Wrapped DEK too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(GCM_TAG_BITS, wrapped.copyOfRange(0, GCM_IV_LEN)),
        )
        return cipher.doFinal(wrapped.copyOfRange(GCM_IV_LEN, wrapped.size))
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "de.gematik.zeta.sdk.storage.dek_wrap_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LEN = 12
        const val GCM_TAG_BITS = 128
    }
}
