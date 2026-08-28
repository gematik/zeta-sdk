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

package de.gematik.zeta.client.data.service.oidc

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.OidcEndpointAware
import de.gematik.zeta.sdk.authentication.oidc.AuthenticationCallback

public class SystemBrowserAuthenticator(
    private val browserLauncher: BrowserLauncher,
) : AuthenticationCallback, OidcEndpointAware {
    override lateinit var resolveAuthorizationEndpoint: suspend () -> String
    override lateinit var resolveBrokerEndpoint: suspend () -> String

    override suspend fun authenticationCb(
        clientId: String,
        requestUri: String,
    ): AuthenticationCallback.AuthInfo {
        val authorizeEndpoint = resolveAuthorizationEndpoint()
        val brokerEndpoint = resolveBrokerEndpoint()
        val authorizeUrl = "$authorizeEndpoint?client_id=$clientId&request_uri=$requestUri"

        Log.d { "[SystemBrowserAuthenticator] opening browser: $authorizeUrl" }
        val finalUrl = browserLauncher.launchAndAwaitCallback(authorizeUrl = authorizeUrl, brokerEndpoint = brokerEndpoint)
        Log.d { "[SystemBrowserAuthenticator] received callback: $finalUrl" }

        return AuthenticationCallback.AuthInfo(finalUrl = finalUrl)
    }
}
