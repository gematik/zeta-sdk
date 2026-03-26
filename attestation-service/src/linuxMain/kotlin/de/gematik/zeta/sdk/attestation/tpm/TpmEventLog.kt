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

package de.gematik.zeta.sdk.attestation.tpm

import de.gematik.zeta.logging.Log
import okio.FileSystem
import okio.Path.Companion.toPath

internal fun readTpmEventLog(): ByteArray {
    val fileSystem = FileSystem.SYSTEM

    val tpmDevices = findTpmDevices()

    if (tpmDevices.isEmpty()) {
        Log.i { "Using Software TPM. Logs not available" }
        return ByteArray(0)
    }

    for (device in tpmDevices) {
        val eventLogPath = getEventLogPath(device) ?: continue

        try {
            val path = eventLogPath.toPath()
            val size = fileSystem.metadata(path).size ?: 0L

            if (size > 0L) {
                return fileSystem.read(path) {
                    readByteArray()
                }
            }
        } catch (e: Exception) {
            Log.e { "Error while getting TPM system logs: ${e.message}" }
            continue
        }
    }

    Log.e { "No TPM logs found" }
    return ByteArray(0)
}

private fun findTpmDevices(): List<String> {
    val fileSystem = FileSystem.SYSTEM
    val tpmClassPath = "/sys/class/tpm".toPath()

    return try {
        if (fileSystem.exists(tpmClassPath)) {
            fileSystem.list(tpmClassPath)
                .map { it.name }
                .filter { it.startsWith("tpm") }
                .sorted()
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        Log.e { "Error while getting list of TPMs: ${e.message}" }
        emptyList()
    }
}

private fun getEventLogPath(tpmDevice: String): String? {
    val fileSystem = FileSystem.SYSTEM

    val modernPath = "/sys/kernel/security/$tpmDevice/binary_bios_measurements"
    if (fileSystem.exists(modernPath.toPath())) {
        return modernPath
    }

    val legacyPath = "/sys/class/tpm/$tpmDevice/device/binary_bios_measurements"
    if (fileSystem.exists(legacyPath.toPath())) {
        return legacyPath
    }

    return null
}
