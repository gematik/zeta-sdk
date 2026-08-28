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
import de.gematik.zeta.logging.Log

internal class ChunkedSettings(
    private val delegate: Settings,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : Settings by delegate {

    init {
        require(chunkSize > 0) { "chunkSize must be greater than zero" }
    }

    override fun putString(key: String, value: String) {
        remove(key)
        if (value.length <= chunkSize) {
            delegate.putString(key, value)
            Log.d { "Stored value as single entry: key=$key, size=${value.length}" }
            return
        }
        val chunks = value.chunked(chunkSize)
        chunks.forEachIndexed { index, chunk ->
            delegate.putString(chunkKey(key, index), chunk)
        }
        delegate.putString(chunkCountKey(key), chunks.size.toString())
        Log.d { "Stored value as ${chunks.size} chunks: key=$key, size=${value.length}" }
    }

    override fun getStringOrNull(key: String): String? {
        val chunkCountRaw = delegate.getStringOrNull(chunkCountKey(key))
            ?: return delegate.getStringOrNull(key)

        val chunkCount = chunkCountRaw.toIntOrNull()
        if (chunkCount == null || chunkCount <= 0) {
            Log.e { "Invalid chunk count '$chunkCountRaw' for key=$key" }
            return null
        }

        Log.d { "Reassembling value from $chunkCount chunks: key=$key" }

        val sb = StringBuilder(chunkCount * chunkSize)
        for (index in 0 until chunkCount) {
            val chunk = delegate.getStringOrNull(chunkKey(key, index))
            if (chunk == null) {
                Log.e { "Missing chunk $index/$chunkCount for key=$key" }
                return null
            }
            sb.append(chunk)
        }

        Log.d { "Reassembled value from $chunkCount chunks: key=$key, size=${sb.length}" }

        return sb.toString()
    }

    override fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override fun remove(key: String) {
        val chunkCountRaw = delegate.getStringOrNull(chunkCountKey(key))
        if (chunkCountRaw != null) {
            val chunkCount = chunkCountRaw.toIntOrNull()
            if (chunkCount != null && chunkCount > 0) {
                for (index in 0 until chunkCount) delegate.remove(chunkKey(key, index))
            } else {
                Log.e { "Invalid chunk count '$chunkCountRaw' while removing key=$key" }
            }
            delegate.remove(chunkCountKey(key))
        }
        delegate.remove(key)
    }

    private fun chunkCountKey(key: String) = "$key$CHUNK_COUNT_SUFFIX"
    private fun chunkKey(key: String, index: Int) = "$key$CHUNK_PREFIX$index"

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 7000
        const val CHUNK_COUNT_SUFFIX = ":chunks"
        const val CHUNK_PREFIX = ":c"
    }
}
