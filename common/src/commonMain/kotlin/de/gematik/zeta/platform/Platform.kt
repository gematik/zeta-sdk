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

package de.gematik.zeta.platform

public sealed class Platform {
    public data object Android : Platform()
    public data object IOS : Platform()

    public sealed class Jvm : Platform() {
        public data object Macos : Jvm()
        public data object Linux : Jvm()
        public data object Windows : Jvm()
    }

    public sealed class Native : Platform() {
        public data object Macos : Native()
        public data object Linux : Native()
        public data object Windows : Native()
    }

    public companion object {
        public val current: Platform by lazy {
            platform()
        }
    }
}

public fun Platform.isApple(): Boolean {
    return when (this) {
        Platform.IOS,
        Platform.Jvm.Macos,
        -> true

        else -> false
    }
}

/**
 * Get current platform
 */
public expect fun platform(): Platform

/**
 * Get current platform info
 */
public data class PlatformInfo(val os: String, val osVersion: String, val arch: String)
public expect fun getPlatformInfo(): PlatformInfo
