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

package de.gematik.zeta.sdk.authentication.smcb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ConnectorErrorTest {

    @Test
    fun constructorStoresFaultCodeAndFaultString() {
        // Arrange & Act
        val error = ConnectorError(
            faultCode = "SOAP-ENV:Server",
            faultString = "Internal error",
            message = "SOAP-ENV:Server - Internal error",
        )

        // Assert
        assertEquals("SOAP-ENV:Server", error.faultCode)
        assertEquals("Internal error", error.faultString)
        assertEquals("SOAP-ENV:Server - Internal error", error.message)
    }

    @Test
    fun constructorWithCause() {
        // Arrange
        val cause = IllegalStateException("root cause")

        // Act
        val error = ConnectorError(
            faultCode = "SOAP-ENV:Client",
            faultString = "Bad request",
            message = "SOAP-ENV:Client - Bad request",
            cause = cause,
        )

        // Assert
        assertSame(cause, error.cause)
        assertEquals("SOAP-ENV:Client", error.faultCode)
    }

    @Test
    fun constructorWithoutCauseDefaultsToNull() {
        // Arrange & Act
        val error = ConnectorError(
            faultCode = "fault",
            faultString = "string",
            message = "msg",
        )

        // Assert
        assertNull(error.cause)
    }

    @Test
    fun isRuntimeException() {
        // Arrange & Act
        val error = ConnectorError("code", "string", "message")

        // Assert
        assertIs<RuntimeException>(error)
    }

    @Test
    fun emptyFaultCodeAndFaultString() {
        // Arrange & Act
        val error = ConnectorError(
            faultCode = "",
            faultString = "",
            message = "",
        )

        // Assert
        assertEquals("", error.faultCode)
        assertEquals("", error.faultString)
        assertEquals("", error.message)
    }
}
