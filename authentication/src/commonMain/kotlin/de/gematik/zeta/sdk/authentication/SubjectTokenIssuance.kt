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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.oidc.TokenIssuanceRequest
import de.gematik.zeta.sdk.tpm.TpmProvider
import kotlin.time.measureTimedValue

class SubjectTokenIssuance(
    private val authConfig: AuthConfig,
    private val authApi: AuthenticationApi,
    private val authStorage: AuthenticationStorage,
    private val tpmProvider: TpmProvider,
    private val clock: () -> Long,
) {
    suspend fun issue(
        request: TokenIssuanceRequest,
        dpopKey: String,
        provider: SubjectTokenProvider,
    ): String {
        val (subjectToken, subjectTime) = measureTimedValue {
            provider.createSubjectToken(
                request.params.clientId,
                dpopKey,
                request.nonce,
                request.tokenEndpoint,
                clock(),
                authConfig.exp,
                tpmProvider,
            )
        }
        Log.d { "[AUTH-TIMING] SubjectTokenIssuance createSubjectToken=$subjectTime" }
        val tokenRequest = AccessTokenRequest(
            grantType = "urn:ietf:params:oauth:grant-type:token-exchange",
            clientId = request.params.clientId,
            requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
            clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            clientAssertion = request.clientAssertionProvider(),
            scope = request.params.scopes.joinToString(" "),
            subjectToken = subjectToken,
            subjectTokenType = "urn:ietf:params:oauth:token-type:jwt",
            audience = request.params.audience,
        )
        val (resp, httpTime) = measureTimedValue { authApi.requestAccessToken(request.tokenEndpoint, tokenRequest, request.dpopProvider(request.tokenEndpoint, null)) }
        Log.d { "[AUTH-TIMING] SubjectTokenIssuance requestAccessToken=$httpTime" }
        authStorage.saveAccessTokens(resp.accessToken, resp.refreshToken, clock() + resp.expiresIn)
        return resp.accessToken
    }
}
