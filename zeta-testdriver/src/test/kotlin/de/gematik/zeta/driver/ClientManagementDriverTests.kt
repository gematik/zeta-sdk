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

import de.gematik.zeta.sdk.authentication.identity.ClientManagementConflictException
import de.gematik.zeta.sdk.authentication.identity.ClientManagementUnauthorizedException
import de.gematik.zeta.sdk.authentication.identity.ClientNotFoundException
import de.gematik.zeta.sdk.authentication.identity.ErrorBody
import de.gematik.zeta.sdk.authentication.identity.PepError
import de.gematik.zeta.sdk.authentication.identity.ProblemCode
import de.gematik.zeta.sdk.authentication.identity.ProblemDetails
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientManagementDriverTests {

    @Test
    fun problem_isForwardedWithGuardStatus() = runTest {
        val problem = ProblemDetails(status = 404, code = ProblemCode.NOT_FOUND, title = "Not Found")
        val e = ClientNotFoundException(guardResponse(HttpStatusCode.NotFound), ErrorBody(problem = problem))

        val (status, body) = clientManagementError(e)

        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals(Json.parseToJsonElement("""{"status":404,"code":"notFound","title":"Not Found"}"""), body)
    }

    @Test
    fun pepError_isForwardedWithGuardStatus() = runTest {
        val pepError = PepError(error = "AccessTokenInvalid", errorDescription = "missing scopes: zeta:client-management")
        val e = ClientManagementUnauthorizedException(guardResponse(HttpStatusCode.Unauthorized), ErrorBody(pepError = pepError))

        val (status, body) = clientManagementError(e)

        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(
            Json.parseToJsonElement("""{"error":"AccessTokenInvalid","error_description":"missing scopes: zeta:client-management"}"""),
            body,
        )
    }

    @Test
    fun guardErrorWithoutParsedBody_forwardsStatusAndMessage() = runTest {
        val e = ClientManagementConflictException(guardResponse(HttpStatusCode.Conflict), ErrorBody())

        val (status, body) = clientManagementError(e)

        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals(Json.parseToJsonElement("""{"message":"${e.message}"}"""), body)
    }

    @Test
    fun invalidInput_isBadRequest() {
        val (status, body) = clientManagementError(IllegalArgumentException("client id must not be blank"))

        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals(Json.parseToJsonElement("""{"message":"client id must not be blank"}"""), body)
    }

    @Test
    fun malformedRequestBody_isBadRequest() {
        val (status, _) = clientManagementError(BadRequestException("Failed to convert request body"))

        assertEquals(HttpStatusCode.BadRequest, status)
    }

    @Test
    fun otherFailure_isInternalServerError() {
        val (status, body) = clientManagementError(IllegalStateException("Client not registered"))

        assertEquals(HttpStatusCode.InternalServerError, status)
        assertEquals(Json.parseToJsonElement("""{"message":"Client not registered"}"""), body)
    }

    @Test
    fun verifyEmailChangeRequest_readsCode() {
        assertEquals(VerifyEmailChangeRequest("123456"), Json.decodeFromString<VerifyEmailChangeRequest>("""{"code":"123456"}"""))
    }

    private suspend fun guardResponse(status: HttpStatusCode): HttpResponse =
        HttpClient(MockEngine { respond("", status) }).get("https://guard.example.com/zeta/clients")
}
