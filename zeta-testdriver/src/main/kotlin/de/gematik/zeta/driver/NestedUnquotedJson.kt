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

package de.gematik.zeta.driver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

public object NestedUnquotedJson : JsonTransformingSerializer<JsonElement>(JsonElement.Companion.serializer()) {
    override fun transformSerialize(element: JsonElement): JsonElement = normalize(element)
    override fun transformDeserialize(element: JsonElement): JsonElement = normalize(element)

    private fun normalize(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> buildJsonObject { el.forEach { (k, v) -> put(k, normalize(v)) } }

        is JsonArray -> buildJsonArray { el.forEach { add(normalize(it)) } }

        is JsonPrimitive -> if (el.isString) {
            val s = el.content.trim()
            if (s.startsWith("{") ||
                s.startsWith("[") ||
                s == "true" ||
                s == "false" ||
                s == "null" ||
                s.firstOrNull()?.let { it.isDigit() || it == '-' || it == '+' } == true
            ) {
                try { normalize(Json.parseToJsonElement(s)) } catch (_: Throwable) { el }
            } else {
                el
            }
        } else {
            el
        }
    }
}
