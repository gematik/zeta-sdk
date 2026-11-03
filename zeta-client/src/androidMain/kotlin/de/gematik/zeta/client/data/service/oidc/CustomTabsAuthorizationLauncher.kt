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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import de.gematik.zeta.client.config.AndroidConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationFailedException
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationLauncher
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public class CustomTabsAuthorizationLauncher(
    private val context: () -> Context = { AndroidConfig.context() },
    private val callbackTimeout: Duration = 5.minutes,
) : AuthorizationLauncher {

    private val currentAttempt = AtomicReference<Job?>(null)

    override suspend fun open(request: AuthorizationRequest): String = coroutineScope {
        val attempt = async(start = CoroutineStart.LAZY) { authorize(request) }
        currentAttempt.getAndSet(attempt)?.let { previous ->
            Log.d { "[CustomTabsAuthorizationLauncher] cancelling previous authorization attempt" }
            previous.cancelAndJoin()
        }
        try {
            attempt.await()
        } finally {
            currentAttempt.compareAndSet(attempt, null)
        }
    }

    private suspend fun authorize(request: AuthorizationRequest): String {
        OidcCallbackHandler.clear()
        try {
            return withTimeout(callbackTimeout) {
                openCustomTab(request.authorizationUrl)
                val oidcCallback = OidcCallbackHandler.awaitOidcCallback().toUri()

                oidcCallback.getQueryParameter("error")?.let { error ->
                    throw AuthorizationFailedException(error, oidcCallback.getQueryParameter("error_description"))
                }
                val code = requireNotNull(oidcCallback.getQueryParameter("code")) {
                    "OIDC callback does not contain 'code'"
                }
                val state = requireNotNull(oidcCallback.getQueryParameter("state")) {
                    "OIDC callback does not contain 'state'"
                }

                val brokerUrl = request.brokerEndpoint.toUri()
                    .buildUpon()
                    .appendQueryParameter("code", code)
                    .appendQueryParameter("state", state)
                    .build()
                    .toString()

                Log.d { "[CustomTabsAuthorizationLauncher] continuing authentication via broker" }
                openCustomTab(brokerUrl)

                OidcCallbackHandler.awaitFinalCallback()
            }
        } catch (_: TimeoutCancellationException) {
            throw AuthorizationFailedException(
                "timeout",
                "No authorization callback received within $callbackTimeout",
            )
        } finally {
            OidcCallbackHandler.clear()
        }
    }

    private fun openCustomTab(url: String) {
        val ctx = context()
        val customTabsIntent = CustomTabsIntent.Builder().build()
        if (ctx !is Activity) {
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            customTabsIntent.launchUrl(ctx, url.toUri())
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No browser available to open the login page", e)
        }
    }
}
