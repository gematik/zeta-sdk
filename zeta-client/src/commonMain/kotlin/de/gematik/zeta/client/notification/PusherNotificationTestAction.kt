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
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.notifications.NotificationClient
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
import de.gematik.zeta.sdk.notifications.model.HistoricNotification
import de.gematik.zeta.sdk.notifications.model.PusherConfig
import de.gematik.zeta.sdk.notifications.model.PusherData
import kotlin.concurrent.Volatile

/**
 * The push test action shared by the mobile platforms (FCM on Android, APNs on iOS). Platforms only
 * supply how the push token is obtained, the push gateway `app_id`, the device name, and access to
 * the SDK's notification client (`ZetaSdkClient.notifications()` exists on Android and iOS only).
 *
 * The SDK session of the last test action is kept in memory so incoming pushes can be decrypted with
 * it; a push delivered to a process where the app hasn't been used since launch therefore cannot be
 * decrypted (see [decryptIncomingPush]).
 *
 * @param appId the push gateway app id; its last segment selects the provider (`firebase` / `apns`).
 */
internal abstract class PusherNotificationTestAction(
    private val appId: String,
    private val deviceDisplayName: String,
) : NotificationTestAction {
    @Volatile
    private var registered = false

    @Volatile
    private var sdkClient: ZetaSdkClient? = null

    /** The platform's current push token, used as the pusher's `pushkey`. */
    protected abstract suspend fun fetchPushToken(): String

    /** Returns `sdkClient.notifications()`; that accessor is not available in common code. */
    protected abstract fun notificationsOf(sdkClient: ZetaSdkClient): NotificationClient

    override suspend fun run(sdkClient: ZetaSdkClient): String {
        val pushKey = fetchPushToken()
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

    override suspend fun getHistory(sdkClient: ZetaSdkClient): List<HistoricNotification> =
        notificationsOf(sdkClient).getNotifications().notifications

    override suspend fun getHistoricNotification(sdkClient: ZetaSdkClient, identifier: String): HistoricNotification =
        notificationsOf(sdkClient).getNotification(identifier)

    /**
     * Decrypts an incoming push with the SDK session from the last test action in this process.
     * Returns `null` when there is none yet, e.g. after a cold start or an SDK rebuild.
     */
    suspend fun decryptIncomingPush(ciphertext: String, timeMessageEncrypted: String): String? {
        val client = sdkClient ?: return null
        return notificationsOf(client).decryptPushNotification(ciphertext, timeMessageEncrypted)
    }

    /** (Re-)registers the pusher for [token]; a no-op until a test action has provided an SDK session. */
    suspend fun registerWithToken(token: String) {
        val client = sdkClient ?: return
        notificationsOf(client).registerPusher(
            PusherConfig(
                pushkey = token,
                appId = appId,
                appDisplayName = "ZETA Demo",
                deviceDisplayName = deviceDisplayName,
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
        val channels = notificationsOf(sdkClient).getAvailableChannels().map { it.copy(status = status) }
        notificationsOf(sdkClient).setLocalChannels(channels)
        return channels
    }
}
