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

package de.gematik.zeta.sdk.notifications

import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.HistoricNotification
import de.gematik.zeta.sdk.notifications.model.HistoricNotifications
import de.gematik.zeta.sdk.notifications.model.Pusher
import de.gematik.zeta.sdk.notifications.model.PusherConfig
import de.gematik.zeta.sdk.notifications.model.PusherEncryption
import de.gematik.zeta.time.SystemZetaClock
import randomUUID
import secureRandom

class NotificationClientImpl internal constructor(
    private val api: NotificationApiClient,
    private val localPusherStorage: LocalPusherStorage,
    private val createEncryption: () -> PusherEncryption,
) : NotificationClient {
    constructor(
        api: NotificationApiClient,
    ) : this(api, InMemoryLocalPusherStorage(), ::defaultPusherEncryption)

    override suspend fun getPushers(): List<Pusher> = api.getPushers()

    override suspend fun registerPusher(registration: PusherConfig) {
        val local = localPusherStorage.get()
        if (local != null && local.pushkey == registration.pushkey && local.appId == registration.appId) {
            return
        }
        if (local != null) {
            api.setPusher(deletion(local.pushkey, local.appId))
        }
        api.setPusher(registration.toPusher(createEncryption()))
        localPusherStorage.save(LocalPusher(registration.pushkey, registration.appId))
    }

    override suspend fun updatePusher(update: PusherConfig) {
        api.setPusher(update.toPusher())
        localPusherStorage.save(LocalPusher(update.pushkey, update.appId))
    }

    override suspend fun deletePusher(pushkey: String, appId: String) {
        api.setPusher(deletion(pushkey, appId))
        val local = localPusherStorage.get()
        if (local != null && local.pushkey == pushkey && local.appId == appId) {
            localPusherStorage.clear()
        }
    }

    override suspend fun getAvailableChannels(): List<Channel> = api.getChannels()

    override suspend fun getLocalChannels(): List<Channel> = api.getChannel(requireLocalPushkey())

    override suspend fun setLocalChannels(channels: List<Channel>) {
        api.setChannel(requireLocalPushkey(), channels)
    }

    override suspend fun decryptPushNotification(ciphertext: String, timeMessageEncrypted: String): String {
        error("decryptPushNotification is not implemented")
    }

    override suspend fun getNotification(notificationId: String): HistoricNotification {
        error("getNotification is not implemented")
    }

    override suspend fun getNotifications(): HistoricNotifications {
        error("getNotifications is not implemented")
    }

    private suspend fun requireLocalPushkey(): String =
        localPusherStorage.get()?.pushkey ?: error("No local pusher is registered")
}

internal fun defaultPusherEncryption(): PusherEncryption {
    val iss = ByteArray(ISS_BYTE_SIZE).also { secureRandom(it) }.toHexString()
    return PusherEncryption(
        method = ENCRYPTION_METHOD,
        timeIssCreated = SystemZetaClock.now().toString().take(YEAR_MONTH_LENGTH),
        iss = iss,
        keyIdentifier = randomUUID(),
    )
}

private fun PusherConfig.toPusher(encryption: PusherEncryption? = null) = Pusher(
    pushkey = pushkey,
    kind = HTTP_KIND,
    appId = appId,
    appDisplayName = appDisplayName,
    deviceDisplayName = deviceDisplayName,
    profileTag = profileTag,
    lang = lang,
    data = data,
    encryption = encryption,
)

private fun deletion(pushkey: String, appId: String) = Pusher(
    pushkey = pushkey,
    kind = null,
    appId = appId,
)

private const val HTTP_KIND = "http"
private const val ENCRYPTION_METHOD = "aes-hmac-sha256"
private const val ISS_BYTE_SIZE = 32
private const val YEAR_MONTH_LENGTH = 7
