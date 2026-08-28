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

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A device pusher registration, per `pusher_get.yaml`/`pusher_post_put_delete.yaml` in
 * `gem-push-notifications-concept/docs_sources/definitions`. Also used as the
 * `POST /pushers/set` request body: setting `kind = null` deletes the pusher identified by
 * [appId]/[pushkey].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Pusher(
    val pushkey: String,
    // Deletion (kind = null) must be sent as an explicit JSON null, not an omitted field, so the
    // request is unambiguous even though null is also this property's Kotlin default value.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val kind: String? = null,
    @SerialName("app_id") val appId: String,
    @SerialName("app_display_name") val appDisplayName: String? = null,
    @SerialName("device_display_name") val deviceDisplayName: String? = null,
    @SerialName("profile_tag") val profileTag: String? = null,
    val lang: String? = null,
    val data: PusherData? = null,
    val encryption: PusherEncryption? = null,
    val append: Boolean? = null,
)

@Serializable
data class PusherData(
    val url: String? = null,
    val format: String? = null,
)

/**
 * Initial shared-secret key material for a pusher, per `A_29968`/`A_29970`. Optional in the
 * contract; required/forbidden depending on the target service.
 */
@Serializable
data class PusherEncryption(
    val method: String,
    @SerialName("time_iss_created") val timeIssCreated: String,
    val iss: String,
    @SerialName("key_identifier") val keyIdentifier: String,
)

@Serializable
internal data class GetPushersResponse(val pushers: List<Pusher> = emptyList())
