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

import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.notifications
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
import de.gematik.zeta.sdk.notifications.model.PusherData
import de.gematik.zeta.sdk.notifications.model.PusherConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val APP_ID = "de.gematik.zeta.client"

internal fun createNotificationTestAction(): NotificationTestAction? = FcmNotificationTestAction

internal suspend fun reregisterPusherForNewFcmToken(token: String) {
    FcmNotificationTestAction.registerWithToken(token)
}

private object FcmNotificationTestAction : NotificationTestAction {
    @Volatile
    private var registered = false

    @Volatile
    private var sdkClient: ZetaSdkClient? = null

    override suspend fun run(sdkClient: ZetaSdkClient): String {
        val pushKey = fetchFcmToken()
        this.sdkClient = sdkClient
        registerWithToken(pushKey)
        return "Pusher registered (…${pushKey.takeLast(8)})"
    }

    override fun hasPusherKey(): Boolean = registered

    override fun reset() {
        registered = false
        sdkClient = null
    }

    override suspend fun subscribeAllChannels(sdkClient: ZetaSdkClient): List<Channel> =
        setAllChannels(sdkClient, ChannelStatus.ENABLED)

    override suspend fun unsubscribeAllChannels(sdkClient: ZetaSdkClient): String {
        val channels = setAllChannels(sdkClient, ChannelStatus.DISABLED)
        return "Unsubscribed all channels (${channels.size})"
    }

    suspend fun registerWithToken(token: String) {
        val client = sdkClient ?: return
        client.notifications().registerPusher(
            PusherConfig(
                pushkey = token,
                appId = APP_ID,
                appDisplayName = "ZETA Demo",
                deviceDisplayName = Build.MODEL,
                lang = "de",
                data = PusherData(url = DIContainer.PUSH_GATEWAY_URL),
            ),
        )
        registered = true
    }

    private suspend fun setAllChannels(
        sdkClient: ZetaSdkClient,
        status: ChannelStatus,
    ): List<Channel> {
        val channels = sdkClient.notifications().getAvailableChannels().map { it.copy(status = status) }
        sdkClient.notifications().setLocalChannels(channels)
        return channels
    }
}

private suspend fun fetchFcmToken(): String = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token unavailable"))
        }
    }
}
