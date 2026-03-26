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
import io.ktor.http.RequestConnectionPoint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.readlink

actual class ProcessMonitor actual constructor(private val allowedExecutables: List<String>) {
    actual fun isRunning(processName: String): Boolean {
        return false
    }

    actual fun findSocketAndPid(origin: RequestConnectionPoint): Int? {
        val localIp = origin.remoteHost
        val remotePort = origin.remotePort
        val socket = findSocket(localIp, remotePort)
        return if (socket != null) {
            val pid = inodeToPid(socket.inode)
            pid
        } else {
            null
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getProcessName(pid: Int?): String? {
        val path = "/proc/$pid/comm"
        val file = fopen(path, "r") ?: return null
        val buf = ByteArray(2048)
        return try {
            if (fgets(buf.refTo(0), buf.size, file) != null) {
                buf.toKString().trim()
            } else {
                null
            }
        } finally {
            fclose(file)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getProcessExecutablePath(pid: Int?): String? {
        if (pid == null) return null
        val path = "/proc/$pid/exe"
        val buf = ByteArray(4096)
        val len = readlink(path, buf.refTo(0), buf.size.convert())
        return if (len > 0) {
            buf.decodeToString(0, len.convert())
        } else {
            null
        }
    }

    actual fun isProcessAllowed(origin: RequestConnectionPoint): Boolean {
        if (allowedExecutables.isEmpty()) return true
        val pid = findSocketAndPid(origin) ?: return false
        val executablePath = getProcessExecutablePath(pid) ?: return false
        return allowedExecutables.any { allowed ->
            val trimmed = allowed.trim()
            executablePath == trimmed || executablePath == trimmed.trimEnd('/')
        }
    }

    private fun hexToIp(hex: String): String {
        val bytes = hex.chunked(2).reversed().map { it.toInt(16) }
        return bytes.joinToString(".")
    }

    private fun hexToIpV6(hex: String): String {
        if (hex.length == 32 && hex.substring(0, 24).equals("00000000000000000000FFFF", ignoreCase = true)) {
            return hexToIp(hex.substring(24))
        }
        if (hex == "00000000000000000000000000000001") {
            return "::1"
        }
        return hexToIp(hex.takeLast(8))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun parseProcNetTcp(filePath: String = "/proc/net/tcp"): List<SocketEntry> {
        val entries = mutableListOf<SocketEntry>()
        val file = fopen(filePath, "r") ?: return entries
        val buf = ByteArray(512)

        try {
            fgets(buf.refTo(0), buf.size, file) // skip header
            while (fgets(buf.refTo(0), buf.size, file) != null) {
                val line = buf.toKString().trim()
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 10) continue

                val local = parts[1]
                val remote = parts[2]
                val inode = parts[9].toLongOrNull() ?: continue

                val (lipHex, lpHex) = local.split(":")
                val (ripHex, rpHex) = remote.split(":")

                entries += SocketEntry(
                    localIp = hexToIp(lipHex),
                    localPort = lpHex.toInt(16),
                    remoteIp = hexToIp(ripHex),
                    remotePort = rpHex.toInt(16),
                    inode = inode,
                )
            }
        } finally {
            fclose(file)
        }

        Log.d { "entries: $entries" }

        return entries
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun parseProcNetTcp6(filePath: String = "/proc/net/tcp6"): List<SocketEntry> {
        val entries = mutableListOf<SocketEntry>()
        val file = fopen(filePath, "r") ?: return entries
        val buf = ByteArray(512)

        try {
            fgets(buf.refTo(0), buf.size, file)
            while (fgets(buf.refTo(0), buf.size, file) != null) {
                val line = buf.toKString().trim()
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 10) continue

                val local = parts[1]
                val remote = parts[2]
                val inode = parts[9].toLongOrNull() ?: continue

                val (lipHex, lpHex) = local.split(":")
                val (ripHex, rpHex) = remote.split(":")

                entries += SocketEntry(
                    localIp = hexToIpV6(lipHex),
                    localPort = lpHex.toInt(16),
                    remoteIp = hexToIpV6(ripHex),
                    remotePort = rpHex.toInt(16),
                    inode = inode,
                )
            }
        } finally {
            fclose(file)
        }

        return entries
    }

    private fun findSocket(remoteIp: String, remotePort: Int): SocketEntry? {
        var sockets = parseProcNetTcp()
        val socket = sockets.find { it.localIp == remoteIp && it.localPort == remotePort }
        if (socket != null) return socket

        sockets = parseProcNetTcp6()
        return sockets.find { it.localPort == remotePort && it.inode > 0 }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun inodeToPid(targetInode: Long): Int? {
        val proc = opendir("/proc") ?: return null
        try {
            while (true) {
                val e = readdir(proc) ?: break
                val pid = e.pointed.d_name.toKString().toIntOrNull() ?: continue

                val fdDir = opendir("/proc/$pid/fd") ?: continue
                try {
                    while (true) {
                        val fdEntry = readdir(fdDir) ?: break
                        val path = "/proc/$pid/fd/${fdEntry.pointed.d_name.toKString()}"
                        val buf = ByteArray(128)
                        val len = readlink(path, buf.refTo(0), buf.size.convert())
                        if (len <= 0) continue

                        val link = buf.decodeToString(0, len.convert())
                        if (link == "socket:[$targetInode]") {
                            return pid
                        }
                    }
                } finally {
                    closedir(fdDir)
                }
            }
        } finally {
            closedir(proc)
        }
        return null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun getEnv(variable: String): String? {
    return getenv(variable)?.toKString()
}
