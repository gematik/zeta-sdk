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

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.darwin.sysctlbyname
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
actual fun collectAppleDeviceInfo(): AppleDeviceInfo {
    val systemVersion = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        "$majorVersion.$minorVersion.$patchVersion"
    }
    return AppleDeviceInfo(systemVersion = systemVersion, systemName = "macOS", deviceModel = hardwareModel())
}

@OptIn(ExperimentalForeignApi::class)
private fun hardwareModel(): String = memScoped {
    val size = alloc<size_tVar>()
    if (sysctlbyname("hw.model", null, size.ptr, null, 0u) != 0 || size.value == 0uL) return@memScoped "unknown"
    val buffer = allocArray<ByteVar>(size.value.toInt())
    if (sysctlbyname("hw.model", buffer, size.ptr, null, 0u) != 0) return@memScoped "unknown"
    buffer.toKString()
}
