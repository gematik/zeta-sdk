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

import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.tpm.Tpm
import de.gematik.zeta.sdk.tpm.TpmStorageImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttestationApiTest {
    val fixedUuid = { "11111111-2222-3333-4444-555555555555" }

    @Test
    fun createClientAssertion_shallReturnJWTWithThreeParts() = runTest {
        // Arrange
        val nonce = "SERVER-NONCE".encodeToByteArray()
        val exp = 1_700_000_000L
        val clientId = "client-sdk"
        val productId = "demo_product"
        val productVersion = "0.2.0"
        val tokenEndpoint = "https://zeta-test.de/token"

        val api = AttestationApiImpl(Tpm.provider(TpmStorageImpl(InMemoryStorage())), fixedUuid)
        val jwt = api.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = nonce,
            clientId = clientId,
            exp = exp,
            tokenEndpoint = tokenEndpoint,
        )

        val parts = jwt.split('.')
        assertEquals(3, parts.size)
    }

    @Test
    fun createClientAssertion_shallReturnValidHeaders() = runTest {
        // Arrange
        val nonce = "SERVER-NONCE".encodeToByteArray()
        val exp = 1_700_000_000L
        val clientId = "client-sdk"
        val productId = "demo_product"
        val productVersion = "0.2.0"
        val tokenEndpoint = "https://zeta-test.de/token"

        val api = AttestationApiImpl(Tpm.provider(TpmStorageImpl(InMemoryStorage())), fixedUuid)
        val jwt = api.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = nonce,
            clientId = clientId,
            exp = exp,
            tokenEndpoint = tokenEndpoint,
        )
        val parts = jwt.split('.')
        val header = decodeJson(parts[0])

        assertEquals("JWT", header["typ"]?.jsonPrimitive?.content)
        assertEquals(AsymAlg.ES256.name, header["alg"]?.jsonPrimitive?.content)
        assertTrue(header.contains("jwk"))
    }

    @Test
    fun createClientAssertion_shallReturnValidPayload() = runTest {
        // Arrange
        val nonce = "SERVER-NONCE".encodeToByteArray()
        val exp = 1_700_000_000L
        val clientId = "client-sdk"
        val productId = "demo_product"
        val productVersion = "0.2.0"
        val tokenEndpoint = "https://zeta-test.de/token"

        val api = AttestationApiImpl(Tpm.provider(TpmStorageImpl(InMemoryStorage())), fixedUuid)
        val jwt = api.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = nonce,
            clientId = clientId,
            exp = exp,
            tokenEndpoint = tokenEndpoint,
        )
        val parts = jwt.split('.')
        val payload = decodeJson(parts[1])

        assertEquals(clientId, payload["iss"]?.jsonPrimitive?.content)
        assertEquals(clientId, payload["sub"]?.jsonPrimitive?.content)
        val aud = payload["aud"]?.jsonArray
        assertNotNull(aud)
        assertTrue(aud.any { it.jsonPrimitive.content == tokenEndpoint })

        assertEquals(exp, payload["exp"]?.jsonPrimitive?.long)
        assertNotNull(payload["jti"]?.jsonPrimitive)
        assertNotNull(payload["client_statement"]?.jsonObject)
    }

    private fun decodeJson(b64: String): JsonObject {
        val pad = (4 - b64.length % 4) % 4
        val padded = b64 + "=".repeat(pad)
        val decoded = Base64.UrlSafe.decode(padded)

        return Json.decodeFromString<JsonObject>(decoded.decodeToString())
    }
}
