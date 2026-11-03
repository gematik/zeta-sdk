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

package de.gematik.zeta.client.notification

import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.notifications
import de.gematik.zeta.sdk.notifications.NotificationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ZetaApnsPush"
private const val MOCK_TOKEN_KEY = "de.gematik.zeta.client.mockApnsToken"
private const val APNS_TOKEN_BYTES = 32
private val APNS_TOKEN_TIMEOUT = 10.seconds

internal actual fun notificationTestAction(): NotificationTestAction? = ApnsNotificationTestAction

/**
 * Entry points for the Swift app delegate: it hands over the APNs device token and asks for the text
 * of pushes that arrive while the app is in the foreground.
 */
public object IosPushBridge {
    internal val apnsToken = MutableStateFlow<String?>(null)

    /** Why the last APNs registration failed, e.g. a missing `aps-environment` entitlement. */
    internal val apnsRegistrationFailure = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The APNs device token as 64 lowercase hex characters; re-registers the pusher when it changes. */
    public fun onApnsToken(hexToken: String) {
        val previous = apnsToken.value
        apnsRegistrationFailure.value = null
        apnsToken.value = hexToken
        if (previous != null && previous != hexToken) {
            scope.launch {
                try {
                    ApnsNotificationTestAction.registerWithToken(hexToken)
                } catch (t: Throwable) {
                    Log.e(throwable = t, tag = TAG) { "Failed to register pusher after APNs token change" }
                }
            }
        }
    }

    public fun onApnsRegistrationFailed(message: String) {
        Log.w(tag = TAG) { "APNs registration failed: $message" }
        apnsRegistrationFailure.value = message
    }

    /**
     * Decrypts an incoming push and returns its banner text, or `null` when it cannot be shown: this
     * process has no SDK session yet (tap Test Push again after an app restart), or the push was
     * encrypted for another pusher, e.g. one left over from an earlier install.
     *
     * Never throws: a Kotlin exception escaping into Swift would terminate the app.
     */
    public suspend fun decryptForDisplay(ciphertext: String, timeMessageEncrypted: String): String? {
        val decrypted = try {
            ApnsNotificationTestAction.decryptIncomingPush(ciphertext, timeMessageEncrypted)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(throwable = t, tag = TAG) { "Dropping encrypted push: it cannot be decrypted with this session's key" }
            return null
        }
        if (decrypted == null) {
            Log.w(tag = TAG) { "Dropping encrypted push: no SDK session in this process yet" }
        }
        return decrypted?.let(::pushDisplayText)
    }
}

/** The push gateway routes on `<bundle id>.apns`: APNs as provider, the bundle id as APNs topic. */
private object ApnsNotificationTestAction : PusherNotificationTestAction(
    appId = "${NSBundle.mainBundle.bundleIdentifier}.apns",
    deviceDisplayName = UIDevice.currentDevice.model,
) {
    override suspend fun fetchPushToken(): String = if (DIContainer.APNS_MOCK) mockApnsToken() else awaitApnsToken()

    override fun notificationsOf(sdkClient: ZetaSdkClient): NotificationClient = sdkClient.notifications()
}

/**
 * Waits for the app delegate to deliver the APNs token or a registration failure. Fails with a
 * readable reason: a plain `withTimeout` would throw a cancellation, which the UI reports as
 * "Cancelled" instead of an error.
 */
private suspend fun awaitApnsToken(): String {
    val (token, failure) = withTimeoutOrNull(APNS_TOKEN_TIMEOUT) {
        combine(IosPushBridge.apnsToken, IosPushBridge.apnsRegistrationFailure, ::Pair)
            .first { (token, failure) -> token != null || failure != null }
    } ?: error(
        "No APNs device token after $APNS_TOKEN_TIMEOUT: allow notifications for this app, " +
            "or set APNS_MOCK=true to use a mock APNs",
    )
    return token ?: error("APNs registration failed: $failure")
}

/**
 * A stable random token in APNs format. A mock APNs ignores it, but the push gateway only accepts
 * hex characters, and a stable value keeps the same pusher across app restarts.
 */
private fun mockApnsToken(): String {
    val defaults = NSUserDefaults.standardUserDefaults
    defaults.stringForKey(MOCK_TOKEN_KEY)?.let { return it }
    val token = Random.nextBytes(APNS_TOKEN_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    defaults.setObject(token, forKey = MOCK_TOKEN_KEY)
    return token
}
