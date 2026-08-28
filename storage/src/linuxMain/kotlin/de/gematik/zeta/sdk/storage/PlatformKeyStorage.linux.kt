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
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun provideSdkStorage(config: StorageConfig.Default): SdkStorage {
    val base = FileSettings(
        config.linuxFilePath?.toPath()
            ?: ((getenv("HOME")?.toKString() ?: "/tmp").toPath() / ".zeta_sdk_storage"),
    )
    val chunkedBase = ChunkedSettings(base)
    return SecureSdkStorage(
        settings = EncryptedSettings(
            chunkedBase,
            AesGcmCipherImpl(),
            config.aesB64Key,
        ),
        secrets = null,
        namespace = config.namespace,
    )
}

class FileSettings @OptIn(ExperimentalForeignApi::class) constructor(
    private val path: Path,
) : Settings {
    private val map = mutableMapOf<String, String>().also { load(it) }

    private fun String.toEntry(): Pair<String, String>? {
        val eq = indexOf('=').takeIf { it > 0 } ?: return null
        return substring(0, eq) to substring(eq + 1)
    }

    private fun load(into: MutableMap<String, String>) {
        if (!FileSystem.SYSTEM.exists(path)) return
        FileSystem.SYSTEM.read(path) { readUtf8() }
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { it.toEntry() }
            .forEach { (k, v) -> into[k] = v }
    }

    private fun save() {
        FileSystem.SYSTEM.sink(path).use { sink ->
            Buffer().apply {
                map.forEach { (k, v) -> writeUtf8("$k=$v\n") }
            }.let { sink.write(it, it.size) }
        }
    }

    override val keys get() = map.keys
    override val size get() = map.size
    override fun clear() { map.clear(); save() }
    override fun remove(key: String) { map.remove(key); save() }
    override fun hasKey(key: String) = map.containsKey(key)
    override fun putString(key: String, value: String) { map[key] = value; save() }
    override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
    override fun getStringOrNull(key: String) = map[key]
    override fun putInt(key: String, value: Int) { map[key] = value.toString(); save() }
    override fun getInt(key: String, defaultValue: Int) = map[key]?.toIntOrNull() ?: defaultValue
    override fun getIntOrNull(key: String) = map[key]?.toIntOrNull()
    override fun putLong(key: String, value: Long) { map[key] = value.toString(); save() }
    override fun getLong(key: String, defaultValue: Long) = map[key]?.toLongOrNull() ?: defaultValue
    override fun getLongOrNull(key: String) = map[key]?.toLongOrNull()
    override fun putFloat(key: String, value: Float) { map[key] = value.toString(); save() }
    override fun getFloat(key: String, defaultValue: Float) = map[key]?.toFloatOrNull() ?: defaultValue
    override fun getFloatOrNull(key: String) = map[key]?.toFloatOrNull()
    override fun putDouble(key: String, value: Double) { map[key] = value.toString(); save() }
    override fun getDouble(key: String, defaultValue: Double) = map[key]?.toDoubleOrNull() ?: defaultValue
    override fun getDoubleOrNull(key: String) = map[key]?.toDoubleOrNull()
    override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString(); save() }
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key]?.toBooleanStrictOrNull() ?: defaultValue
    override fun getBooleanOrNull(key: String) = map[key]?.toBooleanStrictOrNull()
}
