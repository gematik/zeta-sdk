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

package de.gematik.zeta.driver.oidc

import de.gematik.zeta.sdk.authentication.oidc.OtpCallback
import de.gematik.zeta.sdk.authentication.oidc.OtpSubmission
import kotlinx.coroutines.CompletableDeferred

public class TestDriverOtpCallback(
    private var boundEmail: String?,
) : OtpCallback {
    @Volatile
    private var pendingEmail: CompletableDeferred<String>? = null

    @Volatile
    private var pendingOtp: CompletableDeferred<OtpSubmission>? = null

    @Volatile
    public var lastEmailHint: String? = null
        private set

    @Volatile
    public var lastRejected: Boolean = false
        private set

    public fun setBoundEmail(email: String?) {
        boundEmail = email
    }

    override suspend fun awaitEmail(): String {
        boundEmail?.let { return it }

        val deferred = CompletableDeferred<String>()
        pendingEmail = deferred
        return deferred.await()
    }

    override suspend fun awaitOtp(
        emailHint: String?,
        rejected: Boolean,
    ): OtpSubmission {
        lastEmailHint = emailHint
        lastRejected = rejected
        val deferred = CompletableDeferred<OtpSubmission>()
        pendingOtp = deferred
        return deferred.await()
    }

    public fun provideEmail(email: String): Boolean {
        val deferred = pendingEmail ?: return false
        pendingEmail = null
        return deferred.complete(email)
    }

    public fun provideOtp(code: String): Boolean {
        val deferred = pendingOtp ?: return false
        pendingOtp = null
        return deferred.complete(OtpSubmission.Otp(code = code))
    }

    public fun requestResend(): Boolean {
        val deferred = pendingOtp ?: return false
        pendingOtp = null
        return deferred.complete(OtpSubmission.Resend)
    }

    public fun currentlyAwaiting(): String = when {
        pendingEmail != null -> "email"
        pendingOtp != null -> "otp"
        else -> "none"
    }
}
