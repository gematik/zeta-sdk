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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val CHANGE_EMAIL_PATH = "/zeta/identity/email"

@Serializable
data class ChangeEmailRequest(
    @SerialName("new_email") val newEmail: String,
)

/** Response of a successful (202) email change; [status] reports the server-side state. */
@Serializable
data class ChangeEmailResponse(
    val status: String,
)

@Serializable
internal data class ProblemDetails(
    val status: Int? = null,
    val code: String? = null,
    val title: String? = null,
    val detail: String? = null,
)

/** Failed email change; carries the HTTP status and the RFC 7807 problem details, if any. */
class ChangeEmailException(
    val response: HttpResponse?,
    val httpStatus: Int,
    val code: String?,
    val title: String?,
    val detail: String?,
) : Exception(detail ?: title ?: code ?: "Email change failed with HTTP $httpStatus")
