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
 * @property idpIss Issuer URL of the SekIDP to authenticate against (sent as `idp_iss` in the PAR request).
 * @property idpAlias Alias of the SekIDP identity provider broker in Keycloak, used to build the broker relay endpoint (`/broker/{idpAlias}/endpoint`).
 * @property requestUri Base URI the two redirect URIs are derived from. Must match a redirect_uri registered during client registration (DCR).
 * @property authenticationCallback Drives the `/authorize` step and receives the final redirect once the login flow completes.
 * @property otpCallback Prompts for the user's email and OTP code during the email-binding flow.
 */
data class OidcConfig(
    val idpIss: String,
    val idpAlias: String,
    val requestUri: String,
    val authenticationCallback: AuthenticationCallback? = null,
    val otpCallback: OtpCallback,
) {
    val requestUriApp: String get() = "$requestUri/app"
    val requestUriOidc: String get() = "$requestUri/oidc"
}

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
