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

public expect object Log {

    /**
     * Initializes the logger with a default configuration.
     * This method should be called before using any logging methods.
     */
    public fun initDebugLogger()

    public fun clearDestinations()

    /**
     * Logs a debug message.
     * @param throwable An optional throwable to log.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param message A lambda returning the message to log, evaluated lazily.
     */
    public fun d(
        throwable: Throwable? = null,
        tag: String? = null,
        message: () -> String = { "" },
    )

    /**
     * Logs an informational message.
     * @param throwable An optional throwable to log.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param message A lambda returning the message to log, evaluated lazily.
     */
    public fun i(
        throwable: Throwable? = null,
        tag: String? = null,
        message: () -> String = { "" },
    )

    /**
     * Logs a warning message.
     * @param throwable An optional throwable to log.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param message A lambda returning the message to log, evaluated lazily.
     */
    public fun w(
        throwable: Throwable? = null,
        tag: String? = null,
        message: () -> String = { "" },
    )

    /**
     * Logs an error message.
     * @param throwable An optional throwable to log.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param message A lambda returning the message to log, evaluated lazily.
     */
    public fun e(
        throwable: Throwable? = null,
        tag: String? = null,
        message: () -> String = { "" },
    )

    /**
     * Sets the debug mode for the logger.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param isDebug A boolean indicating if debug mode is enabled.
     */
    public fun setDebugMode(isDebug: Boolean)
}
