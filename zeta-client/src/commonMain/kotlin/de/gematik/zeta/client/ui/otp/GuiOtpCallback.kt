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

package de.gematik.zeta.client.ui.otp

import de.gematik.zeta.sdk.authentication.oidc.OtpCallback
import de.gematik.zeta.sdk.authentication.oidc.OtpSubmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public sealed class OtpFlowPrompt {
    public data object CollectEmail : OtpFlowPrompt()
    public data class EnterOtp(
        val emailHint: String?,
        val rejected: Boolean,
    ) : OtpFlowPrompt()
}

public class GuiOtpCallback : OtpCallback {
    private val _state = MutableStateFlow<OtpFlowPrompt?>(null)
    public val state: StateFlow<OtpFlowPrompt?> = _state.asStateFlow()

    private var pendingEmail: CompletableDeferred<String>? = null
    private var pendingOtp: CompletableDeferred<OtpSubmission>? = null

    override suspend fun awaitEmail(): String {
        val deferred = CompletableDeferred<String>()
        pendingEmail = deferred
        _state.value = OtpFlowPrompt.CollectEmail
        return deferred.await()
    }

    override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission {
        val deferred = CompletableDeferred<OtpSubmission>()
        pendingOtp = deferred
        _state.value = OtpFlowPrompt.EnterOtp(emailHint, rejected)
        return deferred.await()
    }

    public fun onEmailEntered(email: String) {
        pendingEmail?.complete(email)
        pendingEmail = null
        _state.value = null
    }

    public fun onOtpEntered(code: String) {
        pendingOtp?.complete(OtpSubmission.Otp(code))
        pendingOtp = null
        _state.value = null
    }

    public fun onResendRequested() {
        pendingOtp?.complete(OtpSubmission.Resend)
        pendingOtp = null
    }

    public fun onCancelled() {
        pendingEmail?.completeExceptionally(LoginCancelledException())
        pendingEmail = null
        pendingOtp?.completeExceptionally(LoginCancelledException())
        pendingOtp = null
        _state.value = null
    }
}

public class LoginCancelledException : Exception("User cancelled the login flow")
