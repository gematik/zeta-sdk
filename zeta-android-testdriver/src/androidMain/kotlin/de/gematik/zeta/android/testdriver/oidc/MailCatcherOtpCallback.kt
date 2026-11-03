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

package de.gematik.zeta.android.testdriver.oidc

import de.gematik.zeta.driver.core.oidc.MailCatcherOtpClient
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.oidc.OtpCallback
import de.gematik.zeta.sdk.authentication.oidc.OtpSubmission
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient

public class MailCatcherOtpCallback(
    private val boundEmail: String?,
    private val mailCatcher: MailCatcherOtpClient,
    private val client: ZetaHttpClient,
) : OtpCallback {

    override suspend fun awaitEmail(): String =
        boundEmail ?: error("OIDC_BINDING_EMAIL required for the bind flow")

    override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission =
        runCatching { mailCatcher.waitForOtp(client) }
            .map { OtpSubmission.Otp(code = it) }
            .getOrElse {
                Log.e(throwable = it) { "No new OTP mail, requesting resend" }
                OtpSubmission.Resend
            }
}
