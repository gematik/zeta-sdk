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

import ServiceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.model.FileIntegrityResult
import de.gematik.zeta.sdk.attestation.model.VerifyIntegrityResponse
import de.gematik.zeta.sdk.attestation.tpm.TpmAccessOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

class FileIntegrity(
    private val tpm: TpmAccessOperations,
    private val fileScanner: FileScannerOperations,
    private val hashCalculator: FileHashCalculatorOperations,
    private val config: ServiceConfig,
) : FileIntegrityOperations {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var monitorJob: Job? = null
    private var expectedHashes: Map<String, String?> = emptyMap()
    private val subscribers = mutableSetOf<(VerifyIntegrityResponse) -> Unit>()

    override fun initialize(resetIntegrity: Boolean): Map<String, String?> {
        val fileHashes = fileScanner.scanFiles(config.files)
        val scannedHash = hashCalculator.computeMasterHash(fileHashes)

        if (resetIntegrity) {
            storeMasterHash(scannedHash)
        }

        expectedHashes = fileHashes

        return fileHashes
    }

    override fun isIntact(): Boolean {
        val fileHashes = try {
            fileScanner.scanFiles(config.files)
        } catch (e: Exception) {
            Log.e { "FileIntegrity.isIntact: scan failed: ${e.message}" }
            return false
        }

        val missingFiles = fileHashes.filter { it.value == null }
        if (missingFiles.isNotEmpty()) {
            Log.w { "FileIntegrity.isIntact: VIOLATION - missing files: ${missingFiles.keys.joinToString()}" }
            return false
        }

        val scannedHash = hashCalculator.computeMasterHash(fileHashes)
        val expectedPcrValue = hashCalculator.computeExpectedPcr(scannedHash)
        val storedPcrValue = obtainMasterHash()

        Log.i { "FileIntegrity.isIntact: expected: ${Base64.encode(expectedPcrValue)}, stored: ${Base64.encode(storedPcrValue)}" }

        return expectedPcrValue.contentEquals(storedPcrValue)
    }

    override fun verifyIntegrity(
        filePaths: List<String>,
    ): VerifyIntegrityResponse {
        val currentExpected = expectedHashes

        val results = filePaths.associateWith { path ->
            val expectedHash = currentExpected[path]

            val actualHash = try {
                hashCalculator.calculateSHA256(path)
            } catch (e: Exception) {
                Log.e { "FileIntegrity.verifyIntegrity: failed for $path: ${e.message}" }
                null
            }

            FileIntegrityResult(
                path = path,
                actualHash = actualHash,
                expectedHash = expectedHash,
                isValid = expectedHash == actualHash,
            )
        }

        return VerifyIntegrityResponse(results, results.all { it.value.isValid })
    }

    override fun currentIntegrityState(): VerifyIntegrityResponse {
        return verifyIntegrity(config.files)
    }

    override fun subscribe(onUpdate: (VerifyIntegrityResponse) -> Unit): () -> Unit {
        val wasEmpty = subscribers.isEmpty()
        subscribers.add(onUpdate)
        if (wasEmpty) {
            startMonitoringIfNeeded()
        }
        return {
            subscribers.remove(onUpdate)
            if (subscribers.isEmpty()) {
                stopIntegrityMonitor()
            }
        }
    }

    override fun stopIntegrityMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        fileScanner.stopMonitoring()
    }

    private fun startMonitoringIfNeeded() {
        if (monitorJob != null) return

        monitorJob = scope.launch {
            fileScanner.startMonitoring(config.files) { _, _ ->
                val state = currentIntegrityState()
                notifySubscribers(state)
            }
        }
    }

    private fun notifySubscribers(state: VerifyIntegrityResponse) {
        subscribers.forEach { subscriber ->
            subscriber(state)
        }
    }

    private fun obtainMasterHash(): ByteArray {
        return tpm.readPCRs(listOf(config.pcrId))[config.pcrId] ?: byteArrayOf()
    }

    private fun storeMasterHash(hash: ByteArray) {
        tpm.resetPCR(config.pcrId)
        tpm.extendPCR(config.pcrId, hash)
    }

    companion object {
        const val PCR_ID = 23
    }
}
