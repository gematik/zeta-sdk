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

import de.gematik.zeta.sdk.authentication.DpopProvider
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.authentication.ScopedAccessTokenProvider
import de.gematik.zeta.sdk.authentication.identity.models.ClientManagementScopes
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientManagementApiTest {

    private val baseUrl = "https://guard.example.com/zeta"
    private val clientsUrl = "$baseUrl/clients"
    private val changeEmailUrl = "$baseUrl/identity/email"
    private val resendUrl = "$changeEmailUrl/resend"
    private val verifyUrl = "$changeEmailUrl/verify"
    private val scopes = setOf(ClientManagementScopes.CLIENT_MANAGEMENT)

    private val requests = mutableListOf<HttpRequestData>()
    private val tokens = FakeScopedTokenProvider()
    private val proofs = RecordingDpopProvider()

    @Test
    fun listClients_sendsGetWithDpopBoundAuthorization() = runTest {
        val api = api(Reply(HttpStatusCode.OK, "[]"))

        api.listClients()

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals(clientsUrl, request.url.toString())
        assertEquals("${HttpAuthHeaders.Dpop} at-1", request.headers[HttpHeaders.Authorization])
        assertEquals("proof-1", request.headers[HttpAuthHeaders.Dpop])
        assertAcceptsJson(request)
        assertEquals(listOf(ProofCall("GET", clientsUrl, "at-1")), proofs.calls)
        assertEquals(listOf(scopes), tokens.requestedScopes)
    }

    @Test
    fun listClients_decodesClientsAndIgnoresUnknownFields() = runTest {
        val body = """
            [
              {"client_id":"c1","name":"Pixel 9","extra":"x"},
              {"client_id":"c2","name":"iPhone 15"}
            ]
        """.trimIndent()
        val api = api(Reply(HttpStatusCode.OK, body))

        val clients = api.listClients()

        assertEquals(
            listOf(
                RegisteredClient(id = "c1", name = "Pixel 9"),
                RegisteredClient(id = "c2", name = "iPhone 15"),
            ),
            clients,
        )
    }

    @Test
    fun listClients_rejectsIdInsteadOfClientId() = runTest {
        val api = api(Reply(HttpStatusCode.OK, """[{"id":"c1","name":"sdk-client"}]"""))

        assertFailsWith<SerializationException> { api.listClients() }
    }

    @Test
    fun listClients_trimsTrailingSlashFromBaseUrl() = runTest {
        val api = api(Reply(HttpStatusCode.OK, "[]"), baseUrlValue = "$baseUrl/")

        api.listClients()

        assertEquals(clientsUrl, requests.single().url.toString())
        assertEquals(clientsUrl, proofs.calls.single().htu)
    }

    @Test
    fun changeEmail_postsJsonBodyWithDpopBoundAuthorization() = runTest {
        val api = api(Reply(HttpStatusCode.Accepted, """{"status":"pending","challenge_type":"email_otp","email_hint":"n***@example.com"}"""))

        api.changeEmail("new@example.com")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(changeEmailUrl, request.url.toString())
        assertEquals("${HttpAuthHeaders.Dpop} at-1", request.headers[HttpHeaders.Authorization])
        assertEquals("proof-1", request.headers[HttpAuthHeaders.Dpop])
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        assertEquals("new@example.com", newEmailOf(request))
        assertEquals(listOf(ProofCall("POST", changeEmailUrl, "at-1")), proofs.calls)
    }

    @Test
    fun changeEmail_returnsPendingChallenge_on202() = runTest {
        val body = """{"status":"pending","challenge_type":"email_otp","email_hint":"m***@example.de"}"""
        val api = api(Reply(HttpStatusCode.Accepted, body))

        val response = api.changeEmail("new@example.com")

        assertEquals(
            ChangeEmailResponse.Pending(challengeType = ChallengeType.EMAIL_OTP, emailHint = "m***@example.de"),
            response,
        )
    }

    @Test
    fun changeEmail_returnsVerified_whenAddressIsAlreadyVerified() = runTest {
        val api = api(Reply(HttpStatusCode.Accepted, """{"status":"verified"}"""))

        assertEquals(ChangeEmailResponse.Verified, api.changeEmail("same@example.com"))
    }

    @Test
    fun resendEmailChangeCode_postsFormWithVerifyType() = runTest {
        val api = api(Reply(HttpStatusCode.Accepted, """{"challenge_type":"email_otp","email_hint":"m***@example.de"}"""))

        val challenge = api.resendEmailChangeCode()

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(resendUrl, request.url.toString())
        assertEquals("${HttpAuthHeaders.Dpop} at-1", request.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.FormUrlEncoded, request.body.contentType?.withoutParameters())
        assertEquals("verify_type=email_otp", bodyOf(request))
        assertEquals(EmailChallenge(challengeType = ChallengeType.EMAIL_OTP, emailHint = "m***@example.de"), challenge)
    }

    @Test
    fun resendEmailChangeCode_noPendingChallenge_mapsToConflictWithProblemCode() = runTest {
        val api = api(Reply(HttpStatusCode.Conflict, problem(409, "noPendingChallenge")))

        val e = assertFailsWith<ClientManagementConflictException> { api.resendEmailChangeCode() }

        assertEquals(ProblemCode.NO_PENDING_CHALLENGE, e.problem?.code)
    }

    @Test
    fun verifyEmailChange_postsFormWithCodeAndVerifyType() = runTest {
        val api = api(Reply(HttpStatusCode.OK, """{"status":"verified"}"""))

        api.verifyEmailChange("123456")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(verifyUrl, request.url.toString())
        assertEquals("${HttpAuthHeaders.Dpop} at-1", request.headers[HttpHeaders.Authorization])
        assertEquals(ContentType.Application.FormUrlEncoded, request.body.contentType?.withoutParameters())
        assertEquals("code=123456&verify_type=email_otp", bodyOf(request))
    }

    @Test
    fun verifyEmailChange_failsOnUnexpectedStatusInBody() = runTest {
        val api = api(Reply(HttpStatusCode.OK, """{"status":"pending","challenge_type":"email_otp","email_hint":"n***@example.com"}"""))

        assertFailsWith<IllegalStateException> { api.verifyEmailChange("123456") }
    }

    @Test
    fun verifyEmailChange_invalidCode_mapsToInvalidRequestWithProblemCode() = runTest {
        val api = api(Reply(HttpStatusCode.BadRequest, problem(400, "invalidCode")))

        val e = assertFailsWith<InvalidClientManagementRequestException> { api.verifyEmailChange("000000") }

        assertEquals(ProblemCode.INVALID_CODE, e.problem?.code)
    }

    @Test
    fun resendEmailChangeCode_unsupportedVerifyType_mapsToInvalidRequestWithProblemCode() = runTest {
        val api = api(Reply(HttpStatusCode.BadRequest, problem(400, "unsupportedVerifyType")))

        val e = assertFailsWith<InvalidClientManagementRequestException> { api.resendEmailChangeCode() }

        assertEquals(ProblemCode.UNSUPPORTED_VERIFY_TYPE, e.problem?.code)
    }

    @Test
    fun verifyEmailChange_noPendingChallenge_mapsToConflictWithProblemCode() = runTest {
        val api = api(Reply(HttpStatusCode.Conflict, problem(409, "noPendingChallenge")))

        val e = assertFailsWith<ClientManagementConflictException> { api.verifyEmailChange("123456") }

        assertEquals(ProblemCode.NO_PENDING_CHALLENGE, e.problem?.code)
    }

    @Test
    fun verifyEmailChange_rejectsBlankCode_withoutTouchingTokenOrNetwork() = runTest {
        val api = api()

        assertFailsWith<IllegalArgumentException> { api.verifyEmailChange(" ") }

        assertTrue(requests.isEmpty())
        assertTrue(tokens.requestedScopes.isEmpty())
    }

    @Test
    fun changeEmail_rejectsBlankEmail_withoutTouchingTokenOrNetwork() = runTest {
        val api = api()

        assertFailsWith<IllegalArgumentException> { api.changeEmail("   ") }

        assertTrue(requests.isEmpty())
        assertTrue(tokens.requestedScopes.isEmpty())
        assertTrue(proofs.calls.isEmpty())
    }

    @Test
    fun deleteClient_sendsDeleteToClientPath() = runTest {
        val api = api(Reply(HttpStatusCode.NoContent))

        api.deleteClient("c1")

        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("$clientsUrl/c1", request.url.toString())
        assertEquals("${HttpAuthHeaders.Dpop} at-1", request.headers[HttpHeaders.Authorization])
        assertEquals(listOf(ProofCall("DELETE", "$clientsUrl/c1", "at-1")), proofs.calls)
    }

    @Test
    fun deleteClient_encodesIdAsSinglePathSegment() = runTest {
        val api = api(Reply(HttpStatusCode.NoContent))

        api.deleteClient("my client")

        assertTrue(requests.single().url.encodedPath.endsWith("/zeta/clients/my%20client"))
        assertEquals("$clientsUrl/my%20client", proofs.calls.single().htu)
    }

    @Test
    fun deleteClient_unexpectedSuccessStatus_isRejected() = runTest {
        assertFailsWith<UnexpectedClientManagementException> { api(Reply(HttpStatusCode.Accepted, "{}")).deleteClient("c1") }
    }

    @Test
    fun deleteClient_foreignClient_mapsToNotFoundWithProblemCode() = runTest {
        val api = api(Reply(HttpStatusCode.NotFound, problem(404, "notFound")))

        val e = assertFailsWith<ClientNotFoundException> { api.deleteClient("foreign") }

        assertEquals(ProblemCode.NOT_FOUND, e.problem?.code)
        assertEquals(HttpStatusCode.NotFound, e.response.status)
    }

    @Test
    fun deleteClient_rejectsBlankId_withoutTouchingTokenOrNetwork() = runTest {
        val api = api()

        assertFailsWith<IllegalArgumentException> { api.deleteClient("") }

        assertTrue(requests.isEmpty())
        assertTrue(tokens.requestedScopes.isEmpty())
    }

    @Test
    fun deleteClient_notFound_mapsToClientNotFoundException() = runTest {
        val api = api(Reply(HttpStatusCode.NotFound, problem(404, "notInSchema", "no such client")))

        val e = assertFailsWith<ClientNotFoundException> { api.deleteClient("unknown") }

        assertNull(e.problem?.code, "code outside the schema enum is dropped")
        assertEquals("no such client", e.problem?.detail)
    }

    @Test
    fun badRequest_mapsToInvalidRequestException() = runTest {
        val api = api(Reply(HttpStatusCode.BadRequest, problem(400, "invalidRequest", "not an email")))

        val e = assertFailsWith<InvalidClientManagementRequestException> { api.changeEmail("nope") }

        assertEquals(ProblemCode.INVALID_REQUEST, e.problem?.code)
        assertTrue("not an email" in e.message.orEmpty())
    }

    @Test
    fun forbidden_mapsToForbiddenException_andIsNotRetried() = runTest {
        val api = api(Reply(HttpStatusCode.Forbidden, problem(403, "internal")))

        assertFailsWith<ClientManagementForbiddenException> { api.deleteClient("foreign") }

        assertEquals(1, requests.size)
        assertTrue(tokens.invalidated.isEmpty())
    }

    @Test
    fun unexpectedStatus_withProblemDetails_mapsToUnexpectedException() = runTest {
        val api = api(Reply(HttpStatusCode.InternalServerError, problem(500, "boom", "server exploded")))

        val e = assertFailsWith<UnexpectedClientManagementException> { api.listClients() }

        assertEquals(ProblemDetails(status = 500, code = null, title = "title", detail = "server exploded"), e.problem)
        assertTrue("500" in e.message.orEmpty())
        assertTrue("server exploded" in e.message.orEmpty())
    }

    @Test
    fun unexpectedStatus_withNonJsonBody_keepsRawBodyInMessage() = runTest {
        val api = api(Reply(HttpStatusCode.BadGateway, "upstream down"))

        val e = assertFailsWith<UnexpectedClientManagementException> { api.listClients() }

        assertNull(e.problem)
        assertTrue("502" in e.message.orEmpty())
        assertTrue("upstream down" in e.message.orEmpty())
    }

    @Test
    fun unexpectedStatus_isNotRetried() = runTest {
        val api = api(Reply(HttpStatusCode.InternalServerError))

        assertFailsWith<UnexpectedClientManagementException> { api.listClients() }

        assertEquals(1, requests.size)
        assertTrue(tokens.invalidated.isEmpty())
    }

    @Test
    fun unauthorized_invalidatesTokenAndRetriesOnceWithFreshTokenAndProof() = runTest {
        val api = api(
            Reply(HttpStatusCode.Unauthorized, problem(401, "unauthorized")),
            Reply(HttpStatusCode.OK, """[{"client_id":"c1","name":"Pixel 9"}]"""),
        )

        val clients = api.listClients()

        assertEquals(listOf(RegisteredClient(id = "c1", name = "Pixel 9")), clients)
        assertEquals(2, requests.size)
        assertEquals(listOf(scopes), tokens.invalidated)
        assertEquals("${HttpAuthHeaders.Dpop} at-1", requests[0].headers[HttpHeaders.Authorization])
        assertEquals("${HttpAuthHeaders.Dpop} at-2", requests[1].headers[HttpHeaders.Authorization])
        assertNotEquals(requests[0].headers[HttpAuthHeaders.Dpop], requests[1].headers[HttpAuthHeaders.Dpop])
        assertEquals(listOf("at-1", "at-2"), proofs.calls.map { it.accessToken })
    }

    @Test
    fun unauthorized_byPep_carriesPepError() = runTest {
        val pepBody = """{"error":"AccessTokenInvalid","error_description":"access token invalid: missing scopes: zeta:client-management","error_uri":"https://guard.example.com/doc/errors/AccessTokenInvalid.html"}"""
        val api = api(
            Reply(HttpStatusCode.Unauthorized, pepBody, pepOrigin = true),
            Reply(HttpStatusCode.Unauthorized, pepBody, pepOrigin = true),
        )

        val e = assertFailsWith<ClientManagementUnauthorizedException> { api.listClients() }

        assertEquals(
            PepError(
                error = "AccessTokenInvalid",
                errorDescription = "access token invalid: missing scopes: zeta:client-management",
                errorUri = "https://guard.example.com/doc/errors/AccessTokenInvalid.html",
            ),
            e.pepError,
        )
        assertNull(e.problem)
    }

    @Test
    fun unauthorized_byAuthorizationServer_carriesUnauthorizedProblem() = runTest {
        val api = api(
            Reply(HttpStatusCode.Unauthorized, problem(401, "unauthorized")),
            Reply(HttpStatusCode.Unauthorized, problem(401, "unauthorized")),
        )

        val e = assertFailsWith<ClientManagementUnauthorizedException> { api.listClients() }

        assertEquals(ProblemCode.UNAUTHORIZED, e.problem?.code)
        assertNull(e.pepError)
    }

    @Test
    fun pepErrorBody_withoutPepOriginHeader_isNotTakenAsPepError() = runTest {
        val api = api(Reply(HttpStatusCode.InternalServerError, """{"error":"x","error_description":"y"}"""))

        val e = assertFailsWith<UnexpectedClientManagementException> { api.listClients() }

        assertNull(e.pepError)
    }

    @Test
    fun unauthorizedTwice_propagatesAfterSingleRetry() = runTest {
        val api = api(
            Reply(HttpStatusCode.Unauthorized),
            Reply(HttpStatusCode.Unauthorized),
        )

        assertFailsWith<ClientManagementUnauthorizedException> { api.deleteClient("c1") }

        assertEquals(2, requests.size)
        assertEquals(1, tokens.invalidated.size)
    }

    @Test
    fun unauthorized_retryResendsTheRequestBody() = runTest {
        val api = api(
            Reply(HttpStatusCode.Unauthorized),
            Reply(HttpStatusCode.Accepted, """{"status":"verified"}"""),
        )

        api.changeEmail("new@example.com")

        assertEquals(listOf("new@example.com", "new@example.com"), requests.map { newEmailOf(it) })
    }

    @Test
    fun dpopProof_htmAndHtuAlwaysMatchTheRequestActuallySent() = runTest {
        val api = api(
            Reply(HttpStatusCode.OK, "[]"),
            Reply(HttpStatusCode.Accepted, """{"status":"verified"}"""),
            Reply(HttpStatusCode.Accepted, """{"challenge_type":"email_otp"}"""),
            Reply(HttpStatusCode.OK, """{"status":"verified"}"""),
            Reply(HttpStatusCode.NoContent),
        )

        api.listClients()
        api.changeEmail("new@example.com")
        api.resendEmailChangeCode()
        api.verifyEmailChange("123456")
        api.deleteClient("c1")

        assertEquals(requests.size, proofs.calls.size)
        requests.zip(proofs.calls).forEach { (request, proof) ->
            assertEquals(request.method.value, proof.htm, "htm mismatch for ${request.url}")
            assertEquals(request.url.toString(), proof.htu, "htu mismatch for ${request.method.value}")
        }
    }

    private data class Reply(val status: HttpStatusCode, val body: String = "", val pepOrigin: Boolean = false)

    private fun api(vararg replies: Reply, baseUrlValue: String = baseUrl): ClientManagementApiClientImpl {
        val queue = ArrayDeque(replies.toList())
        val engine = MockEngine { request ->
            requests += request
            val reply = queue.removeFirstOrNull() ?: error("Unexpected request: ${request.method.value} ${request.url}")
            respond(
                content = reply.body,
                status = reply.status,
                headers = if (reply.pepOrigin) {
                    headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()), "zeta-error-origin" to listOf("pep"))
                } else {
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                },
            )
        }
        val httpClient = ZetaHttpClient(HttpClient(engine) { install(ContentNegotiation) { json() } })
        return ClientManagementApiClientImpl(
            httpClient = httpClient,
            baseUrl = baseUrlValue,
            tokenProvider = tokens,
            dpopProvider = proofs,
        )
    }

    private fun problem(status: Int, code: String, detail: String? = null): String {
        val detailField = if (detail != null) ",\"detail\":\"$detail\"" else ""
        return "{\"status\":$status,\"code\":\"$code\",\"title\":\"title\"$detailField}"
    }

    private fun assertAcceptsJson(request: HttpRequestData) {
        val accept = request.headers.getAll(HttpHeaders.Accept).orEmpty().joinToString(",")
        assertTrue(ContentType.Application.Json.toString() in accept, "Accept was '$accept'")
    }

    private suspend fun bodyOf(request: HttpRequestData): String = request.body.toByteArray().decodeToString()

    private suspend fun newEmailOf(request: HttpRequestData): String? =
        Json.parseToJsonElement(bodyOf(request)).jsonObject["new_email"]?.jsonPrimitive?.content

    private class FakeScopedTokenProvider : ScopedAccessTokenProvider {
        val requestedScopes = mutableListOf<Set<String>>()
        val invalidated = mutableListOf<Set<String>>()
        private var issued = 0

        override suspend fun getAccessToken(scopes: Set<String>): String {
            requestedScopes += scopes
            return "at-${++issued}"
        }

        override suspend fun invalidate(scopes: Set<String>) {
            invalidated += scopes
        }
    }

    private data class ProofCall(val htm: String, val htu: String, val accessToken: String)

    private class RecordingDpopProvider : DpopProvider {
        val calls = mutableListOf<ProofCall>()

        override suspend fun createDpopProof(htm: String, htu: String, accessToken: String): String {
            calls += ProofCall(htm, htu, accessToken)
            return "proof-${calls.size}"
        }
    }
}
