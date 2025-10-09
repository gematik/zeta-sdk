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

package de.gematik.zeta.sdk.flow.handler

import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.flow.CapabilityHandler
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import io.ktor.http.HttpHeaders

@Suppress("FunctionOnlyReturningConstant")
class EnsureAccessTokenHandler(
    val accessTokenProvider: AccessTokenProvider,

) : CapabilityHandler {

    override fun canHandle(need: FlowNeed): Boolean = need == FlowNeed.Authentication

    override suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult {
        // TODO: uncomment when registration is enabled
        // val clientId = ClientRegistrationStorage(ctx.storage).getClientId()
        // checkNotNull(clientId)

        val token = accessTokenProvider.getValidToken()

        return CapabilityResult.RetryRequest { req ->
            if (!req.headers.contains(HttpHeaders.Authorization)) {
                req.headers[HttpHeaders.Authorization] = "Bearer $token"
            }
        }
    }
}
