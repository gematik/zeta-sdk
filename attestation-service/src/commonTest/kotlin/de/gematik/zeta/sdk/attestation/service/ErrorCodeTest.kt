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

package de.gematik.zeta.sdk.attestation.service

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorCodeTest {

    @Test
    fun errorCode_hasAllExpectedValues() {
        // Act
        val values = ErrorCode.entries

        // Assert
        assertEquals(5, values.size)
        assertTrue(values.contains(ErrorCode.TPM_NOT_AVAILABLE))
        assertTrue(values.contains(ErrorCode.TPM_QUOTE_ERROR))
        assertTrue(values.contains(ErrorCode.INVALID_ARGUMENT))
        assertTrue(values.contains(ErrorCode.INTERNAL_ERROR))
        assertTrue(values.contains(ErrorCode.PROCESS_NOT_ALLOWED))
    }

    @Test
    fun errorCode_valueOf_returnsCorrectValue() {
        // Act & Assert
        assertEquals(ErrorCode.TPM_NOT_AVAILABLE, ErrorCode.valueOf("TPM_NOT_AVAILABLE"))
        assertEquals(ErrorCode.PROCESS_NOT_ALLOWED, ErrorCode.valueOf("PROCESS_NOT_ALLOWED"))
    }

    @Test
    fun tpmException_formatsMessageWithHexCode() {
        // Arrange & Act
        val exception = TpmException("TPM failure", 0x000001A4u)

        // Assert
        assertEquals("TPM failure (error code: 0x1a4)", exception.message)
        assertEquals(0x000001A4u, exception.code)
    }

    @Test
    fun tpmException_withZeroCode() {
        // Arrange & Act
        val exception = TpmException("No error", 0u)

        // Assert
        assertEquals("No error (error code: 0x0)", exception.message)
    }

    @Test
    fun tpmException_isException() {
        // Arrange & Act
        val exception = TpmException("test", 1u)

        // Assert
        assertIs<Exception>(exception)
    }

    @Test
    fun serviceError_constructsWithDefaults() {
        // Arrange & Act
        val error = ServiceError(
            code = ErrorCode.INTERNAL_ERROR,
            message = "Something went wrong",
        )

        // Assert
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code)
        assertEquals("Something went wrong", error.message)
        assertTrue(error.details.isEmpty())
    }

    @Test
    fun serviceError_constructsWithDetails() {
        // Arrange & Act
        val error = ServiceError(
            code = ErrorCode.TPM_QUOTE_ERROR,
            message = "Quote failed",
            details = mapOf("pcr" to "23", "reason" to "locked"),
        )

        // Assert
        assertEquals("23", error.details["pcr"])
        assertEquals("locked", error.details["reason"])
    }

    @Test
    fun serviceError_serializesAndDeserializes() {
        // Arrange
        val error = ServiceError(
            code = ErrorCode.INVALID_ARGUMENT,
            message = "Bad input",
            details = mapOf("field" to "challenge"),
        )

        // Act
        val encoded = Json.encodeToString(ServiceError.serializer(), error)
        val decoded = Json.decodeFromString(ServiceError.serializer(), encoded)

        // Assert
        assertEquals(error.code, decoded.code)
        assertEquals(error.message, decoded.message)
        assertEquals(error.details, decoded.details)
    }
}
