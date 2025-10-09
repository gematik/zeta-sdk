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

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier

public object Log {
    private var debugLog: DebugAntilog? = null
    private var isDebug: Boolean = false
    private var logLevel: LogLevel = LogLevel.DEBUG

    public fun isEnabled(level: LogLevel): Boolean {
        return isDebug && level.ordinal >= logLevel.ordinal
    }

    /**
     * Initializes the logger with a default configuration.
     * This method should be called before using any logging methods.
     */
    public fun initDebugLogger() {
        isDebug = true
        if (debugLog == null) {
            DebugAntilog().also {
                Napier.base(it)
                debugLog = it
            }
        }
    }

    public fun clearDestinations() {
        Napier.takeLogarithm()
        debugLog = null
    }

    public fun register(destination: LogDestination) {
        Napier.run {
            base(
                object : Antilog() {
                    override fun performLog(
                        priority: LogLevel,
                        tag: String?,
                        throwable: Throwable?,
                        message: String?,
                    ) {
                        destination.log(
                            throwable = throwable,
                            tag = tag,
                            message = message.orEmpty(),
                            level = priority,
                        )
                    }
                },
            )
        }
    }

    /**
     * Logs a debug message.
     * @param throwable An optional throwable to log.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param message A lambda returning the message to log, evaluated lazily.
     */
    public inline fun d(
        throwable: Throwable? = null,
        tag: String? = null,
        message: () -> String = { "" },
    ) {
        log(throwable, tag, LogLevel.DEBUG, message)
    }

    /**
     * Logs an informational message.
     * @param throwable An optional throwable to log.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param message A lambda returning the message to log, evaluated lazily.
     */
    public inline fun i(
        throwable: Throwable? = null,
        tag: String? = null,
        message: () -> String = { "" },
    ) {
        log(throwable, tag, LogLevel.INFO, message)
    }

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
    ) {
        log(throwable, tag, LogLevel.WARNING, message)
    }

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
    ) {
        log(throwable, tag, LogLevel.ERROR, message)
    }

    /**
     * Sets the debug mode for the logger.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param isDebug A boolean indicating if debug mode is enabled.
     */
    public fun setDebugMode(isDebug: Boolean) {
        this.isDebug = isDebug
    }

    /**
     * Sets the log level for the logger.
     * @param level The log level to set.
     */
    public fun setLogLevel(level: LogLevel) {
        logLevel = level
    }

    /**
     * Internal logging function used by all log level methods.
     * @param throwable Optional exception to include in the log.
     * @param tag Optional tag to be displayed, without one the Logger tries to use the stacktrace to get one
     * @param level The severity level for the log message.
     * @param message Lambda providing the log message, evaluated lazily.
     */
    public inline fun log(
        throwable: Throwable?,
        tag: String? = null,
        level: LogLevel,
        message: () -> String,
    ) {
        if (isEnabled(level)) {
            Napier.log(
                priority = level,
                tag = tag,
                throwable = throwable,
                message = message.invoke(),
            )
        }
    }
}

public interface LogDestination {
    public fun log(
        throwable: Throwable?,
        level: LogLevel,
        tag: String?,
        message: String,
    )
}
