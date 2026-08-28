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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Client for the guard's identity endpoint that changes the email address bound to this client.
 * Used by `ZetaSdkClient.changeEmail()`.
 */
class ChangeEmailClient(
    private val httpClient: ZetaHttpClient,
    tpmProvider: TpmProvider,
    clock: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val assertionFactory = IdentityClientAssertionFactory(tpmProvider, clock)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends `POST {issuer}/zeta/identity/email` with body `{"new_email": ...}`, authenticated
     * by a TPM-signed `Client-Assertion` header. Expects `202 Accepted`; any other status
     * is thrown as [ChangeEmailException] with the RFC 7807 problem details of the response.
     */
    suspend fun changeEmail(issuer: String, clientId: String, newEmail: String): ChangeEmailResponse {
        require(newEmail.isNotBlank()) { "new_email must not be blank" }
        val realmIssuer = issuer.trimEnd('/')
        val url = "$realmIssuer$CHANGE_EMAIL_PATH"
        val assertion = assertionFactory.create(clientId, realmIssuer, HttpMethod.Post.value, url)
        val response = httpClient.post(url) {
            header(HttpAuthHeaders.CLIENT_ASSERTION, assertion)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            contentType(ContentType.Application.Json)
            setBody(ChangeEmailRequest(newEmail))
        }
        val bodyText = response.bodyAsText()
        if (response.status == HttpStatusCode.Accepted) {
            return json.decodeFromString(bodyText)
        }
        val problem = runCatching { json.decodeFromString<ProblemDetails>(bodyText) }.getOrNull()
        throw ChangeEmailException(
            response = response.raw,
            httpStatus = response.status.value,
            code = problem?.code,
            title = problem?.title,
            detail = problem?.detail ?: bodyText.ifBlank { null },
        )
    }
}
