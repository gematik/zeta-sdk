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

package de.gematik.zeta.driver

import de.gematik.zeta.sdk.notifications.NotificationClient
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.PusherConfig
import de.gematik.zeta.sdk.notifications.model.PusherData
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/**
 * Test-driver HTTP handlers that drive the SDK's [NotificationClient] (push pusher/channel
 * management), following the control-handler idiom in `DriverUtils.kt`. Routes are wired in
 * `TestDriver.kt`. Only the SDK-implemented operations are exposed; the unimplemented
 * `decryptPushNotification`/`getNotification(s)` methods are intentionally not routed.
 */

/**
 * Serializable request body for pusher register/update. Mirrors [PusherConfig], which is not
 * itself `@Serializable`; [toPusherConfig] adapts it for the SDK call.
 */
@Serializable
public data class PusherConfigRequest(
    val pushkey: String,
    val appId: String,
    val appDisplayName: String? = null,
    val deviceDisplayName: String? = null,
    val profileTag: String? = null,
    val lang: String? = null,
    val data: PusherData? = null,
) {
    public fun toPusherConfig(): PusherConfig = PusherConfig(
        pushkey = pushkey,
        appId = appId,
        appDisplayName = appDisplayName,
        deviceDisplayName = deviceDisplayName,
        profileTag = profileTag,
        lang = lang,
        data = data,
    )
}

/** Serializable request body for [setLocalChannels]; the SDK's own wire DTO is internal. */
@Serializable
public data class SetChannelsRequest(val channels: List<Channel>)

public suspend fun getPushers(call: ApplicationCall, client: NotificationClient) {
    try {
        call.respond(HttpStatusCode.OK, client.getPushers())
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun registerPusher(call: ApplicationCall, client: NotificationClient) {
    try {
        client.registerPusher(call.receive<PusherConfigRequest>().toPusherConfig())
        call.respond(HttpStatusCode.OK, "registered")
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun updatePusher(call: ApplicationCall, client: NotificationClient) {
    try {
        client.updatePusher(call.receive<PusherConfigRequest>().toPusherConfig())
        call.respond(HttpStatusCode.OK, "updated")
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun deletePusher(call: ApplicationCall, client: NotificationClient) {
    try {
        val pushkey = call.request.queryParameters["pushkey"]
        val appId = call.request.queryParameters["appId"]
        if (pushkey.isNullOrEmpty() || appId.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "query parameters 'pushkey' and 'appId' are required")
            return
        }
        client.deletePusher(pushkey, appId)
        call.respond(HttpStatusCode.OK, "deleted")
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun getAvailableChannels(call: ApplicationCall, client: NotificationClient) {
    try {
        call.respond(HttpStatusCode.OK, client.getAvailableChannels())
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun getLocalChannels(call: ApplicationCall, client: NotificationClient) {
    try {
        call.respond(HttpStatusCode.OK, client.getLocalChannels())
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun setLocalChannels(call: ApplicationCall, client: NotificationClient) {
    try {
        client.setLocalChannels(call.receive<SetChannelsRequest>().channels)
        call.respond(HttpStatusCode.OK, "channels updated")
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}
