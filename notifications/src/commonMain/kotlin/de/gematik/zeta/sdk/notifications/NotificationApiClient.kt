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
import de.gematik.zeta.sdk.notifications.model.Pusher

/**
 * HTTP contract for the Notification Service device API (`GET /pushers`, `POST /pushers/set`,
 * `GET /channels`, `GET`/`POST /channels/{pushkey}`). Implemented by [NotificationApiClientImpl]
 * and used inside the SDK; the embedding app talks to [NotificationClient] instead.
 *
 * All methods may throw [NotificationApiException] subtypes.
 */
interface NotificationApiClient {
    /** The pushers registered for the authenticated user. */
    suspend fun getPushers(): List<Pusher>

    /**
     * Registers or updates a pusher; `pusher.kind = null` deregisters the pusher identified by
     * `pusher.appId`/`pusher.pushkey`. `pusher.encryption` carries the ISS key material on
     * registration.
     */
    suspend fun setPusher(pusher: Pusher)

    /** The channels available for the authenticated user and their default status. */
    suspend fun getChannels(): List<Channel>

    /** The channel configuration of one device, identified by [pushkey]. */
    suspend fun getChannel(pushkey: String): List<Channel>

    /** Sets [channels] for the device identified by [pushkey]; omitted channels are unchanged. */
    suspend fun setChannel(pushkey: String, channels: List<Channel>)
}
