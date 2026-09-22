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

package de.gematik.zeta.sdk.authentication.identity

import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ChangeEmailClientTest {
    private val issuer = "http://localhost:8080/realms/zeta-guard"
    private val clientId = "mobile-client-id"
    private val newEmail = "new@example.de"
    private val kid = "instance-key-kid"
    private val uuid = Uuid.parse("11111111-2222-3333-4444-555555555555")

    @Test
    fun changeEmail_postsJsonBodyWithClientAssertionOnly() = runTest {
        var seen: HttpRequestData? = null
        val client = clientResponding(HttpStatusCode.Accepted, """{"status":"verified"}""") { seen = it }

        val response = client.changeEmail(issuer, clientId, newEmail)
        val request = checkNotNull(seen)

        assertEquals("verified", response.status)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/realms/zeta-guard/zeta/identity/email", request.url.encodedPath)
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        assertEquals("""{"new_email":"$newEmail"}""", (request.body as TextContent).text)
        assertEquals(3, request.headers[HttpAuthHeaders.CLIENT_ASSERTION]?.split(".")?.size)
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpAuthHeaders.Dpop])
        assertTrue(request.headers[HttpHeaders.Accept]?.contains("application/json") == true)
    }

    @Test
    fun changeEmail_usesIssuerAsAudienceAndRequestUrlAsHtu() = runTest {
        var assertion: String? = null
        val client = clientResponding(HttpStatusCode.Accepted, """{"status":"verified"}""") {
            assertion = it.headers[HttpAuthHeaders.CLIENT_ASSERTION]
        }

        client.changeEmail("$issuer/", clientId, newEmail)

        val payload = decodeJwtPayload(assertion!!)
        assertEquals(issuer, payload["aud"])
        assertEquals("$issuer/zeta/identity/email", payload["htu"])
        assertEquals("POST", payload["htm"])
        assertEquals(clientId, payload["iss"])
        assertEquals(clientId, payload["sub"])
    }

    @Test
    fun changeEmail_mapsProblemJsonToChangeEmailException() = runTest {
        val client = clientResponding(
            HttpStatusCode.Unauthorized,
            """{"status":401,"code":"popRequired","title":"Client assertion required","detail":"missing assertion"}""",
        )

        val exception = assertFailsWith<ChangeEmailException> {
            client.changeEmail(issuer, clientId, newEmail)
        }

        assertEquals(401, exception.httpStatus)
        assertEquals("popRequired", exception.code)
        assertEquals("Client assertion required", exception.title)
        assertEquals("missing assertion", exception.detail)
    }

    @Test
    fun changeEmail_mapsInvalidRequestToChangeEmailException() = runTest {
        val client = clientResponding(
            HttpStatusCode.BadRequest,
            """{"status":400,"code":"invalidRequest","title":"Invalid request","detail":"new_email is not a valid email"}""",
        )

        val exception = assertFailsWith<ChangeEmailException> {
            client.changeEmail(issuer, clientId, newEmail)
        }

        assertEquals(400, exception.httpStatus)
        assertEquals("invalidRequest", exception.code)
    }

    @Test
    fun changeEmail_rejectsBlankEmailBeforeRequest() = runTest {
        val client = clientResponding(HttpStatusCode.Accepted, """{"status":"verified"}""") {
            error("request must not be sent")
        }

        assertFailsWith<IllegalArgumentException> {
            client.changeEmail(issuer, clientId, "   ")
        }
    }

    @Test
    fun changeEmail_usesSystemClockByDefault() = runTest {
        val engine = MockEngine {
            respond(
                """{"status":"verified"}""",
                HttpStatusCode.Accepted,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = ChangeEmailClient(
            httpClient = ZetaHttpClientBuilder().build(engine),
            tpmProvider = FakeTpmProvider(kid, uuid),
            clock = { SystemZetaClock.now().epochSeconds },
        )

        val response = client.changeEmail(issuer, clientId, newEmail)

        assertEquals("verified", response.status)
    }

    @Test
    fun changeEmail_usesRawBodyWhenProblemJsonIsInvalid() = runTest {
        val client = clientResponding(HttpStatusCode.InternalServerError, "not-json")

        val exception = assertFailsWith<ChangeEmailException> {
            client.changeEmail(issuer, clientId, newEmail)
        }

        assertEquals(500, exception.httpStatus)
        assertNull(exception.code)
        assertNull(exception.title)
        assertEquals("not-json", exception.detail)
        assertNotNull(exception.response)
    }

    @Test
    fun changeEmail_usesNullDetailWhenErrorBodyIsBlank() = runTest {
        val client = clientResponding(HttpStatusCode.InternalServerError, "  ")

        val exception = assertFailsWith<ChangeEmailException> {
            client.changeEmail(issuer, clientId, newEmail)
        }

        assertEquals(500, exception.httpStatus)
        assertNull(exception.detail)
        assertEquals("Email change failed with HTTP 500", exception.message)
    }

    @Test
    fun changeEmail_fallsBackToRawBodyWhenProblemHasNoDetail() = runTest {
        val body = """{"status":409,"code":"conflict","title":"Conflict"}"""
        val client = clientResponding(HttpStatusCode.Conflict, body)

        val exception = assertFailsWith<ChangeEmailException> {
            client.changeEmail(issuer, clientId, newEmail)
        }

        assertEquals(409, exception.httpStatus)
        assertEquals("conflict", exception.code)
        assertEquals("Conflict", exception.title)
        assertEquals(body, exception.detail)
    }

    private fun clientResponding(
        status: HttpStatusCode,
        body: String,
        onRequest: (HttpRequestData) -> Unit = {},
    ): ChangeEmailClient {
        val engine = MockEngine { request ->
            onRequest(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return ChangeEmailClient(
            httpClient = ZetaHttpClientBuilder().build(engine),
            tpmProvider = FakeTpmProvider(kid, uuid),
            clock = { 1_730_000_000L },
        )
    }

    private fun decodeJwtPayload(jwt: String): Map<String, String> {
        val json = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                .decode(jwt.split(".")[1])
                .decodeToString(),
        ).jsonObject
        return json.mapValues { it.value.jsonPrimitive.content }
    }
}
