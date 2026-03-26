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

package de.gematik.zeta.sdk.attestation

import AttestationService
import ServiceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.config.CliArgs
import de.gematik.zeta.sdk.attestation.config.Config
import de.gematik.zeta.sdk.attestation.config.Config.getConfig
import de.gematik.zeta.sdk.attestation.interfaces.FileHashCalculator
import de.gematik.zeta.sdk.attestation.interfaces.FileHashCalculatorOperations
import de.gematik.zeta.sdk.attestation.interfaces.FileIntegrity
import de.gematik.zeta.sdk.attestation.interfaces.FileIntegrity.Companion.PCR_ID
import de.gematik.zeta.sdk.attestation.interfaces.FileScanner
import de.gematik.zeta.sdk.attestation.interfaces.FileScannerOperations
import de.gematik.zeta.sdk.attestation.interfaces.ProcessMonitor
import de.gematik.zeta.sdk.attestation.interfaces.ProcessMonitorOperations
import de.gematik.zeta.sdk.attestation.server.AttestationServer
import de.gematik.zeta.sdk.attestation.tpm.TpmAccess
import de.gematik.zeta.sdk.attestation.tpm.TpmAccessOperations
import io.ktor.http.RequestConnectionPoint

fun main(args: Array<String>) {
    CliArgs.init(args)
    val configFile = CliArgs.get("config-file")
    checkNotNull(configFile) { "Command line argument '--config-file' is missing" }

    Config.init(configFile)
    val files = getConfig("FILES")?.split(",")?.map { it.trim() }
    val serverPort = getConfig("SERVER_PORT")?.toInt() ?: 8081
    val pcrId = getConfig("PCR_ID")?.toInt() ?: PCR_ID
    val allowedExecutables = getConfig("ALLOWED_EXECUTABLES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val enableFileIntegrity = getConfig("ENABLE_FILE_INTEGRITY")?.toBooleanStrict() ?: true
    val enableQuote = getConfig("ENABLE_QUOTE")?.toBooleanStrict() ?: true
    val enablePcrLog = getConfig("ENABLE_PCR_LOG")?.toBooleanStrict() ?: true
    val enableEKCertificate = getConfig("ENABLE_EK_CERTIFICATE")?.toBooleanStrict() ?: true
    val enableProcessOrigin = getConfig("ENABLE_PROCESS_ORIGIN")?.toBooleanStrict() ?: true
    checkNotNull(files) { "Config property 'FILES' is missing" }

    val config = ServiceConfig(
        files = files,
        port = serverPort,
        pcrId = pcrId,
        resetFileIntegrity = CliArgs.contains("reset-file-integrity"),
        enableFileIntegrity = enableFileIntegrity,
        enableQuote = enableQuote,
        enablePcrLog = enablePcrLog,
        enableEKCertificate = enableEKCertificate,
        enableProcessOrigin = enableProcessOrigin,
        allowedExecutables = allowedExecutables,
    )

    // Prepare components
    val tpm = TpmAccess()
    if (!tpm.isAvailable()) {
        Log.i { "TMP not available" }
    } else {
        Log.i { "TMP available" }
    }
    // testPCR(tpm)
    // testGetQuote(tpm)
    // testTPMKeyStorage(tpm)

    val tpmOps = TpmAccessAdapter(tpm)
    val processMonitorOps = ProcessMonitorAdapter(ProcessMonitor(config.allowedExecutables))
    val hashCalculatorOps = FileHashCalculatorAdapter(FileHashCalculator)
    val fileScannerOps = FileScannerAdapter(FileScanner())

    val fileIntegrity = FileIntegrity(
        tpm = tpmOps,
        fileScanner = fileScannerOps,
        hashCalculator = hashCalculatorOps,
        config = config,
    )

    // Start server
    val service = AttestationService(
        tpm = tpmOps,
        monitor = processMonitorOps,
        fileScanner = fileScannerOps,
        fileIntegrity = fileIntegrity,
        config = config,
    )
    service.initialize()

    val server = AttestationServer(config, service)
    server.start()
}

fun testPCR(tpm: TpmAccess) {
    val initial = tpm.readPCRs(listOf(10))
    Log.d { "Initial: ${initial[23]?.toHexString()}" }

    val hash = byteArrayOf(
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
        0x6c.toByte(), 0xa1.toByte(), 0x3d.toByte(), 0x52.toByte(),
    )

    tpm.extendPCR(23, hash)

    val updated = tpm.readPCRs(listOf(23))
    Log.d { "After extend:  ${updated[23]?.toHexString()}" }

    tpm.resetPCR(23)
    val reset = tpm.readPCRs(listOf(23))

    Log.d { "After reset:  ${reset[23]?.toHexString()}" }
}

fun testGetQuote(tpm: TpmAccess) {
    Log.i { "TEST GENERATE QUOTE" }

    try {
        if (!tpm.isAvailable()) {
            Log.d { "TPM not available" }
            return
        }
        Log.i { "TPM is available" }

        val nonce = generateRandomNonce(32)

        val result = tpm.generateQuote(nonce, listOf(23))
        Log.d { "QUOTE:" + result.quote }
        Log.d { "SIGNATURE:" + result.signature }
        Log.d { "ATTESTATION KEY:" + result.attestationKey }
    } catch (e: Exception) {
        Log.d { e.message.toString() }
    }
}

fun generateRandomNonce(size: Int): ByteArray {
    return ByteArray(size) {
        (kotlin.random.Random.nextInt(256)).toByte()
    }
}

private class TpmAccessAdapter(private val delegate: TpmAccess) : TpmAccessOperations {
    override fun isAvailable() = delegate.isAvailable()
    override fun readPCRs(pcrSelection: List<Int>) = delegate.readPCRs(pcrSelection)
    override fun extendPCR(pcrIndex: Int, data: ByteArray) = delegate.extendPCR(pcrIndex, data)
    override fun resetPCR(pcrIndex: Int) = delegate.resetPCR(pcrIndex)
    override fun getEventLog() = delegate.getEventLog()
    override fun getEKCertificateChain() = delegate.getEKCertificateChain()
    override fun provisionAttestationKey() = delegate.provisionAttestationKey()
    override fun removeAttestationKey() = delegate.removeAttestationKey()
    override fun generateQuote(attChallengeBytes: ByteArray, pcrSelection: List<Int>) = delegate.generateQuote(attChallengeBytes, pcrSelection)
}

private class FileScannerAdapter(private val delegate: FileScanner) : FileScannerOperations {
    override fun scanFiles(files: List<String>) = delegate.scanFiles(files)
    override fun startMonitoring(files: List<String>, onModified: (String, String) -> Unit) = delegate.startMonitoring(files, onModified)
    override fun stopMonitoring() = delegate.stopMonitoring()
}

private class FileHashCalculatorAdapter(private val delegate: FileHashCalculator) : FileHashCalculatorOperations {
    override fun calculateSHA256(filePath: String) = delegate.calculateSHA256(filePath)
    override fun computeExpectedPcr(hash: ByteArray) = delegate.computeExpectedPcr(hash)
    override fun computeMasterHash(fileHashes: Map<String, String?>) = delegate.computeMasterHash(fileHashes)
}

private class ProcessMonitorAdapter(private val delegate: ProcessMonitor) : ProcessMonitorOperations {
    override fun isRunning(processName: String) = delegate.isRunning(processName)
    override fun findSocketAndPid(origin: RequestConnectionPoint) = delegate.findSocketAndPid(origin)
    override fun getProcessName(pid: Int?) = delegate.getProcessName(pid)
    override fun getProcessExecutablePath(pid: Int?) = delegate.getProcessExecutablePath(pid)
    override fun isProcessAllowed(origin: RequestConnectionPoint) = delegate.isProcessAllowed(origin)
}
