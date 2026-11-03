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
package de.gematik.zeta.sdk.authentication.oidc

/**
 * Configuration for the OIDC authentication flow against the SekIDP.
 *
 * @property redirectUri Final redirect URI of the app, where Keycloak returns the authorization code
 *   at the end of the login. Must match a redirect_uri registered during client registration (DCR).
 * @property oidcRedirectUri Intermediate redirect URI where the SekIDP returns its `code` and `state`,
 *   which are then relayed to the Keycloak broker. Must also be registered during DCR.
 * @property idpIss Issuer URL of the SekIDP to authenticate against (sent as `idp_iss` in the PAR request).
 * @property idpAlias Alias of the SekIDP identity provider broker in Keycloak, used to build the broker relay endpoint (`/broker/{idpAlias}/endpoint`).
 * @property authorizationLauncher Opens the login in the system browser and returns the final redirect to [redirectUri].
 * @property otpCallback Prompts for the user's email and OTP code during the email-binding flow.
 */
data class OidcConfig(
    val redirectUri: String,
    val oidcRedirectUri: String,
    val idpIss: String,
    val idpAlias: String,
    val authorizationLauncher: AuthorizationLauncher,
    val otpCallback: OtpCallback,
)

interface OtpCallback {
    suspend fun awaitEmail(): String
    suspend fun awaitOtp(
        emailHint: String?,
        rejected: Boolean,
    ): OtpSubmission
}

sealed class OtpSubmission {
    data class Otp(val code: String) : OtpSubmission()
    data object Resend : OtpSubmission()
}

/**
 * Opens the OIDC login in the system browser and returns the final redirect.
 *
 * The SDK drives the OIDC flow: it creates PKCE and `state`, sends the pushed authorization
 * request (PAR), builds the authorization URL, validates the result and exchanges the code
 * for tokens. The launcher handles only the part that needs the app: showing the login in a
 * browser and catching the redirects that come back to the app.
 *
 *
 * A launcher completes two browser steps:
 *
 * 1. Opens [AuthorizationRequest.authorizationUrl] and waits for the redirect to
 *    [AuthorizationRequest.oidcRedirectUri]. That redirect carries the identity provider's
 *    `code` and `state`, or `error` and `error_description` if the login failed.
 * 2. Sends the **same browser** to [AuthorizationRequest.brokerEndpoint] with that `code` and
 *    `state` appended as query parameters, and waits for the redirect to
 *    [AuthorizationRequest.redirectUri].
 *
 */
fun interface AuthorizationLauncher {
    /**
     * Runs the browser part of the login and returns the final redirect URL.
     *
     * @param request URLs for this login, provided by the SDK.
     * @return The complete redirect URL received at [AuthorizationRequest.redirectUri],
     *   including its query, for example `de.gematik.zeta://app?code=…&state=…`.
     *   Return it unchanged: the SDK reads `code`, `state` and `error` from it and checks
     *   `state` itself.
     * @throws AuthorizationFailedException if the identity provider redirects with `error`
     *   in step 1, or the user cancels the login.
     * @throws kotlinx.coroutines.CancellationException if the calling coroutine is cancelled.
     *   Implementations should close or cancel any open browser session.
     *
     */
    suspend fun open(request: AuthorizationRequest): String
}

/**
 * URLs for one login, provided by the SDK.
 *
 * @property authorizationUrl URL to open in the browser to start the login.
 * @property redirectUri Final app redirect. Reaching it ends the login.
 * @property oidcRedirectUri Intermediate redirect with the identity provider's `code` and `state`.
 * @property brokerEndpoint Keycloak endpoint to send that `code` and `state` to.
 */
public data class AuthorizationRequest(
    val authorizationUrl: String,
    val redirectUri: String,
    val oidcRedirectUri: String,
    val brokerEndpoint: String,
)

class AuthorizationFailedException(
    val error: String,
    val description: String?,
) : Exception("Authorization failed: $error${description?.let { " ($it)" } ?: ""}")
