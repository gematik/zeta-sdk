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

import de.gematik.zeta.sdk.configuration.WellKnownSchemaValidationImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [WellKnownSchemaValidationImpl].
 */
class WellKnownValidationTest {
    private val sut = WellKnownSchemaValidationImpl()

    @Test
    fun validate_invalidSchema_returnsFalse() =
        runTest {
            // Arrange
            val resource = Json.encodeToString(DummyJson(""))

            // Act
            val result = sut.validate(resource, dummySchema)

            // Arrange
            assertFalse(result)
        }

    fun validate_validSchema_returnsTrue() =
        runTest {
            // Arrange
            val resource = Json.encodeToString(DummyJson("https://test-issuer"))

            // Act
            val result = sut.validate(resource, dummySchema)

            // Arrange
            assertTrue(result)
        }

    private val dummySchema: String =
        """
        {
            "${'$'}schema" : "http://json-schema.org/draft-07/schema#",
            "type" : "object",
            "properties" : {
                "issuer" : {
                  "type" : "string",
                  "format" : "uri",
                  "description" : "The URL of the issuer."
                }
            },
            "required" : [ "issuer"]
        }
        """.trimIndent()

    @Serializable
    private data class DummyJson(
        val issuer: String,
    )
}
