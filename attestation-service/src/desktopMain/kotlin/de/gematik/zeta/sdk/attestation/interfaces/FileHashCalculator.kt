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

package de.gematik.zeta.sdk.attestation.interfaces

import de.gematik.zeta.logging.Log
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use

actual object FileHashCalculator {
    actual fun calculateSHA256(filePath: String): String {
        val path = filePath.toPath()
        val fileSystem = FileSystem.SYSTEM

        val hashingSink = HashingSink.sha256(blackholeSink())
        Log.i { "calculateSHA256: $path" }

        fileSystem.source(path).buffer().use { source ->
            hashingSink.use { sink ->
                source.readAll(sink)
            }
        }

        return hashingSink.hash.hex()
    }

    actual fun computeMasterHash(fileHashes: Map<String, String?>): ByteArray {
        val hashingSink = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()

        fileHashes
            .entries
            .sortedBy { it.key }
            .forEach { (path, hashHex) ->
                buffer.writeUtf8(path)
                hashingSink.write(buffer, buffer.size)

                val hashBytes = hashHex.orEmpty().decodeHex()

                Log.i { "hashBytes: $hashBytes" }

                buffer.write(hashBytes)
                hashingSink.write(buffer, buffer.size)
            }

        hashingSink.close()
        return hashingSink.hash.toByteArray()
    }

    fun computeSHA256(data: ByteArray): ByteArray {
        val hashingSink = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()

        buffer.write(data)
        hashingSink.write(buffer, buffer.size)
        hashingSink.close()

        return hashingSink.hash.toByteArray()
    }

    actual fun computeExpectedPcr(hash: ByteArray): ByteArray {
        val zeroPcr = ByteArray(32)
        return computeSHA256(zeroPcr + hash)
    }
}
