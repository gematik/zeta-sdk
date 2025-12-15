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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.logging.Log
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.Json

/**
 * Contract for validating a well-known JSON document against a JSON Schema.
 */
interface WellKnownSchemaValidation {
    suspend fun validate(resource: String, schema: String): Boolean
}

/**
 * JSON Schema–based validator for well-known documents.
 *
 */
class WellKnownSchemaValidationImpl : WellKnownSchemaValidation {
    /**
     * Validates [resource] against [schema] using [JsonSchema].
     *
     * - Collects all [ValidationError]s and logs them if validation fails.
     *
     * @param resource JSON instance to validate.
     * @param schema JSON Schema definition.
     * @return `true` when validation succeeds; `false` otherwise.
     * @throws Throwable if the schema is invalid or cannot be parsed by the underlying library.
     */
    override suspend fun validate(resource: String, schema: String): Boolean {
        val errors = mutableListOf<ValidationError>()
        val jsonSchema = JsonSchema.fromDefinition(schema)
        val instance = json.parseToJsonElement(resource)

        val isValid = jsonSchema.validate(instance, errors::add)

        if (!isValid) {
            Log.e { "The validation of the well-known failed with following errors:" }
            errors.forEach { Log.e { it.message } }
        }

        return isValid
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}

/**
 * Identifiers of supported well-known document types.
 */
enum class WellKnownTypes {
    AUTHORIZATION_METADATA,
    RESOURCE_METADATA,
}
