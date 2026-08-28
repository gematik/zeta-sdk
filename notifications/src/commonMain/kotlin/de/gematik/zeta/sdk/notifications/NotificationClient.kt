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

/**
 * Public client for the Notification Service of the guard deployment this SDK instance is
 * bound to. Obtained via `ZetaSdkClient.notifications()`.
 *
 * Consumers see only the domain operations — token acquisition, scopes, DPoP binding, service
 * discovery, and ISS encryption happen inside the SDK. All methods may throw
 * [NotificationApiException] subtypes; a failed discovery of the Notification Service surfaces
 * on the first operation, not at accessor time.
 */
interface NotificationClient {
    /** The pushers registered for the authenticated user. */
    suspend fun getPushers(): List<Pusher>

    /** Registers a pusher; ISS key material is derived and attached inside the SDK. */
    suspend fun registerPusher(registration: PusherConfig)

    /** Updates the pusher identified by [PusherConfig.appId]/[PusherConfig.pushkey]. */
    suspend fun updatePusher(update: PusherConfig)

    /**
     * Deregisters the pusher identified by [pushkey] and [appId].
     */
    suspend fun deletePusher(pushkey: String, appId: String)

    /** The channels available for the authenticated user and their default status. */
    suspend fun getAvailableChannels(): List<Channel>

    /**
     * The channel configuration of the local device. A `pushkey` is not required: the last
     * pusher registered via [registerPusher] is used.
     */
    suspend fun getLocalChannels(): List<Channel>

    /**
     * Sets [channels] for the local device; omitted channels are unchanged. A `pushkey` is not
     * required: the last pusher registered via [registerPusher] is used.
     */
    suspend fun setLocalChannels(channels: List<Channel>)

    /**
     * Decrypts an encrypted push payload. A `pushkey` is not required: the key chain of the
     * last pusher registered via [registerPusher] is used.
     */
    suspend fun decryptPushNotification(ciphertext: String, timeMessageEncrypted: String): String

    suspend fun getNotification(notificationId: String): HistoricNotification

    suspend fun getNotifications(): HistoricNotifications
}
