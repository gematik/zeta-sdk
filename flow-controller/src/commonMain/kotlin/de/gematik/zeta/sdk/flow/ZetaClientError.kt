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

package de.gematik.zeta.sdk.flow

sealed class ZetaClientError(message: String) : Exception(message) {
    data class BadRequest(val detail: String = "Bad request") : ZetaClientError(detail)
    class Forbidden : ZetaClientError("Access denied")
    class NotFound : ZetaClientError("Resource not found")
    class MethodNotAllowed : ZetaClientError("HTTP method not supported")
    class Conflict : ZetaClientError("Client already registered")
    class RateLimited : ZetaClientError("Rate limited")
    data class ServerError(val code: Int) : ZetaClientError("Server error $code")
    data class Unknown(val code: Int) : ZetaClientError("Unhandled status $code")
    data class AslError(val errorCode: Int, val detail: String) :
        ZetaClientError("ASL protocol error $errorCode: $detail")
    class StepUpFailed : ZetaClientError("Step-up authentication already attempted — aborting")
    data class PrerequisiteFailed(val code: String, val detail: String) :
        ZetaClientError("Prerequisite failed [$code]: $detail")
}
