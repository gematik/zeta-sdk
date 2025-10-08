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

import de.gematik.zeta.sdk.authentication.model.AccessTokenClaims
import de.gematik.zeta.sdk.authentication.model.AccessTokenHeader
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

internal object AccessTokenUtility {
    fun create(header: AccessTokenHeader, payload: AccessTokenClaims): String {
        // encoding
        val jsonHeader = Json.encodeToString(header)
        val jsonPayload = Json.encodeToString(payload)

        // base64
        val base64Header = Base64.encode(jsonHeader.toByteArray())
        val base64Payload = Base64.encode(jsonPayload.toByteArray())

        return "$base64Header.$base64Payload"
    }

    fun addSignature(token: String, signature: String): String = "$token.$signature"
}
