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

@file:OptIn(ExperimentalForeignApi::class)

package de.gematik.zeta.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.GetSystemInfo
import platform.windows.GetVersionExW
import platform.windows.OSVERSIONINFOW
import platform.windows.PROCESSOR_ARCHITECTURE_AMD64
import platform.windows.PROCESSOR_ARCHITECTURE_ARM
import platform.windows.PROCESSOR_ARCHITECTURE_ARM64
import platform.windows.PROCESSOR_ARCHITECTURE_INTEL
import platform.windows.SYSTEM_INFO
import platform.windows.VER_PLATFORM_WIN32_NT
import platform.windows.VER_PLATFORM_WIN32_WINDOWS

public actual fun platform(): Platform = Platform.Native.Windows

public actual fun getPlatformInfo(): PlatformInfo = memScoped {
    val osInfo = alloc<OSVERSIONINFOW>()
    osInfo.dwOSVersionInfoSize = sizeOf<OSVERSIONINFOW>().toUInt()

    val result = GetVersionExW(osInfo.ptr)
    if (result == 0) error("GetVersionExW: result = $result")

    val osName = when (osInfo.dwPlatformId.toInt()) {
        VER_PLATFORM_WIN32_NT -> "Windows NT"
        VER_PLATFORM_WIN32_WINDOWS -> "Windows 9x"
        else -> "Windows"
    }
    val osVersion = "${osInfo.dwMajorVersion}.${osInfo.dwMinorVersion} (Build ${osInfo.dwBuildNumber})"

    val sysInfo = alloc<SYSTEM_INFO>()
    GetSystemInfo(sysInfo.ptr)

    val osArch = when (sysInfo.wProcessorArchitecture.toInt()) {
        PROCESSOR_ARCHITECTURE_AMD64 -> "x86_64"
        PROCESSOR_ARCHITECTURE_INTEL -> "x86"
        PROCESSOR_ARCHITECTURE_ARM -> "ARM"
        PROCESSOR_ARCHITECTURE_ARM64 -> "ARM64"
        else -> "Unknown"
    }

    PlatformInfo(osName, osVersion, osArch)
}
