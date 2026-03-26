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
import io.github.irgaly.kfswatch.KfsDirectoryWatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

actual class FileScanner {

    private val watcher = KfsDirectoryWatcher(CoroutineScope(Dispatchers.IO))

    actual fun scanFiles(files: List<String>): Map<String, String?> {
        return files.associateWith { filePath ->
            return@associateWith try {
                FileHashCalculator.calculateSHA256(filePath)
            } catch (e: Exception) {
                Log.e { "FileScanner.scanFiles: ${e.message}" }
                null
            }
        }
    }

    @Suppress("SpreadOperator")
    actual fun startMonitoring(files: List<String>, onModified: (String, String) -> Unit) {
        val currentDir = currentDir()
        val normalFiles = files.map {
            currentDir.resolve(it, normalize = true).toString()
        }
        val folderList = normalFiles
            .map { it.toPath().parent.toString() }
            .distinct()

        Log.d { "startMonitoring: $folderList, $normalFiles" }

        runBlocking {
            watcher.add(*folderList.toTypedArray())
            watcher.onEventFlow
                .onEach { Log.d { "onEventFlow: ${it.path}, ${it.event}" } }
                .map {
                    Pair(
                        it.targetDirectory.toPath().resolve(it.path).toString(),
                        it.event.toString(),
                    )
                }
                .filter {
                    it.first in normalFiles
                }
                .collect {
                    onModified(it.first, it.second)
                }
        }
    }

    actual fun stopMonitoring() {
        runBlocking {
            watcher.removeAll()
            watcher.close()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun currentDir(): Path = memScoped {
        val pwd = FileSystem.SYSTEM.canonicalize(".".toPath())
        return pwd
    }
}
