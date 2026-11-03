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

import io.ktor.client.statement.HttpResponse

/** [pepError] is set when the PEP rejects, [problem] when the authorization server does. */
sealed class ClientManagementException(
    val response: HttpResponse,
    val body: ErrorBody,
    message: String,
) : Exception(message) {
    val problem: ProblemDetails? get() = body.problem
    val pepError: PepError? get() = body.pepError
}

data class ErrorBody(
    val problem: ProblemDetails? = null,
    val pepError: PepError? = null,
)

private fun ErrorBody.reason(): String? =
    problem?.detail ?: problem?.code?.name ?: pepError?.errorDescription

class InvalidClientManagementRequestException(response: HttpResponse, body: ErrorBody) :
    ClientManagementException(response, body, "Invalid client-management request: ${body.reason()}")

class ClientManagementUnauthorizedException(response: HttpResponse, body: ErrorBody) :
    ClientManagementException(response, body, "Not authorized for client management: ${body.reason()}")

class ClientManagementForbiddenException(response: HttpResponse, body: ErrorBody) :
    ClientManagementException(response, body, "Forbidden: ${body.reason()}")

class ClientNotFoundException(response: HttpResponse, body: ErrorBody) :
    ClientManagementException(response, body, "Client not found")

class ClientManagementConflictException(response: HttpResponse, body: ErrorBody) :
    ClientManagementException(response, body, "Conflict: ${body.reason()}")

class UnexpectedClientManagementException(response: HttpResponse, httpStatus: Int, body: ErrorBody, rawBody: String?) :
    ClientManagementException(response, body, "Unexpected client-management response $httpStatus: ${body.reason() ?: rawBody}")
