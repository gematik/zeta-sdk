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

package de.gematik.zeta.sdk.authentication

import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Utility object for creating and manipulating access tokens.
 */
internal object AccessTokenUtility {
    /**
     * Creates a JWT-like token from a header and payload.
     *
     * The header and payload are serialized to JSON and then Base64 URL-safe encoded.
     * The resulting strings are concatenated with a period in between.
     *
     * @param H The type of the header.
     * @param P The type of the payload.
     * @param header The header object.
     * @param payload The payload object.
     * @return A string representing the unsigned token in the format "base64(header).base64(payload)".
     */
    inline fun <reified H, reified P> create(header: H, payload: P): String {
        // encoding
        val jsonHeader = Json.encodeToString(header)
        val jsonPayload = Json.encodeToString(payload)

        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        // base64
        val base64Header = base64.encode(jsonHeader.toByteArray())
        val base64Payload = base64.encode(jsonPayload.toByteArray())

        return "$base64Header.$base64Payload"
    }

    /**
     * Appends a signature to an existing token.
     *
     * @param token The token string, usually in the format "base64(header).base64(payload)".
     * @param signature The signature to append.
     * @return The signed token in the format "token.signature".
     */
    fun addSignature(token: String, signature: String): String = "$token.$signature"
}
