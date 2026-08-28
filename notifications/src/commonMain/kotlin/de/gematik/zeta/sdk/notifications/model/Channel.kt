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

package de.gematik.zeta.sdk.notifications.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per `channels_get.yaml`/`channels_post.yaml`: `NOT_SET` is a distinct wire value from
 * `DISABLED`. The RS treats it identically to `DISABLED` when aggregating (A_29972), but the SDK
 * must pass it through unchanged rather than reinterpreting it client-side.
 */
@Serializable
enum class ChannelStatus {
    @SerialName("enabled") ENABLED,

    @SerialName("disabled") DISABLED,

    @SerialName("not_set") NOT_SET,
}

/** A channel's configuration for one device (`pushkey`), per `GET /channels(/{pushkey})`. */
@Serializable
data class Channel(
    val id: String,
    val status: ChannelStatus,
)

@Serializable
internal data class ChannelsResponse(val channels: List<Channel> = emptyList())

@Serializable
internal data class SetChannelsRequest(val channels: List<Channel>)
