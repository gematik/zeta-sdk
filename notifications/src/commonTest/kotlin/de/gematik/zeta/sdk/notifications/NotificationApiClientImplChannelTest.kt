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

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `getChannels()`/`getChannel(pushkey)`/`setChannel(pushkey, ...)` against the
 * `channels_get.yaml`/`channels_post.yaml` contract, device-individual per `pushkey`.
 * In particular, `not_set` must round-trip unchanged — never reinterpreted as `disabled` on the
 * SDK side; that aggregation is RS-side logic.
 */
class NotificationApiClientImplChannelTest {
    @Test
    fun getChannels_callsChannelsWithoutPushkey() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond(
                content = """{"channels":[{"id":"channel1","status":"enabled"},{"id":"channel2","status":"not_set"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)

        val channels = client.getChannels()

        assertEquals(HttpMethod.Get, seenRequest?.method)
        assertEquals("/channels", seenRequest?.url?.encodedPath)
        assertEquals(listOf(Channel("channel1", ChannelStatus.ENABLED), Channel("channel2", ChannelStatus.NOT_SET)), channels)
    }

    @Test
    fun getChannel_callsChannelsOfDeviceWithUrlEncodedPushkey() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond(
                content = """{"channels":[{"id":"channel1","status":"disabled"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)

        // pushkey may contain characters requiring path-segment encoding (base64 tokens use '/', '=').
        val pushkey = "Xp/MzCt8/9DcSNE9cuiaoT5Ac55job3TdLSSmtmYl4A="
        val channels = client.getChannel(pushkey)

        assertEquals(HttpMethod.Get, seenRequest?.method)
        val requestedPath = seenRequest?.url?.encodedPath.orEmpty()
        assertTrue(requestedPath.startsWith("/channels/"))
        assertEquals(pushkey, requestedPath.removePrefix("/channels/").decodeURLPart())
        assertEquals(listOf(Channel("channel1", ChannelStatus.DISABLED)), channels)
    }

    @Test
    fun getChannel_preservesNotSetStatusWithoutReinterpretingAsDisabled() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"channels":[{"id":"channel1","status":"not_set"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)

        val channels = client.getChannel("some-device")

        assertEquals(ChannelStatus.NOT_SET, channels.single().status)
    }

    @Test
    fun setChannel_postsChannelUpdatesToChannelsOfDevice() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)

        client.setChannel("device-1", listOf(Channel("channel1", ChannelStatus.ENABLED), Channel("channel2", ChannelStatus.DISABLED)))

        assertEquals(HttpMethod.Post, seenRequest?.method)
        assertEquals("/channels/device-1", seenRequest?.url?.encodedPath)
        val body = (seenRequest?.body as TextContent).text
        assertTrue(body.contains("\"id\":\"channel1\""))
        assertTrue(body.contains("\"status\":\"enabled\""))
        assertTrue(body.contains("\"status\":\"disabled\""))
    }
}
