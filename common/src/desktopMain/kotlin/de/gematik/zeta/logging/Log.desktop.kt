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

package de.gematik.zeta.logging

public actual object Log {

    private var isDebug: Boolean = false

    public actual fun initDebugLogger() {
        isDebug = true
    }

    public actual fun clearDestinations() {
    }

    public actual fun d(
        throwable: Throwable?,
        tag: String?,
        message: () -> String,
    ) {
    }

    public actual fun i(
        throwable: Throwable?,
        tag: String?,
        message: () -> String,
    ) {
    }

    public actual fun w(
        throwable: Throwable?,
        tag: String?,
        message: () -> String,
    ) {
    }

    public actual fun e(
        throwable: Throwable?,
        tag: String?,
        message: () -> String,
    ) {
    }

    public actual fun setDebugMode(isDebug: Boolean) {
        this.isDebug = isDebug
    }
}
