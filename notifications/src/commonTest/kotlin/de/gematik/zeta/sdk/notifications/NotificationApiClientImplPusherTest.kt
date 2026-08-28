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
import de.gematik.zeta.sdk.notifications.model.Pusher
import de.gematik.zeta.sdk.notifications.model.PusherData
import de.gematik.zeta.sdk.notifications.model.PusherEncryption
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `getPushers()`/`setPusher(...)` against the `pusher_get.yaml`/`pusher_post_put_delete.yaml`
 * contract, including the optional `encryption` object.
 */
class NotificationApiClientImplPusherTest {
    @Test
    fun getPushers_callsGetPushersAndDeserializesEncryptionObject() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond(
                content = """
                {
                  "pushers": [
                    {
                      "pushkey": "Xp/MzCt8/9DcSNE9cuiaoT5Ac55job3TdLSSmtmYl4A=",
                      "kind": "http",
                      "app_id": "de.gematik.fdv",
                      "app_display_name": "FdV",
                      "device_display_name": "Alice's Phone",
                      "lang": "de-DE",
                      "data": { "url": "https://push-gateway.example/push/v1/" },
                      "encryption": {
                        "method": "aes-hmac-sha256",
                        "time_iss_created": "2026-07",
                        "iss": "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                        "key_identifier": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
                      }
                    }
                  ]
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)

        val pushers = client.getPushers()

        assertEquals(HttpMethod.Get, seenRequest?.method)
        assertEquals("/pushers", seenRequest?.url?.encodedPath)
        assertEquals(1, pushers.size)
        val encryption = pushers.single().encryption
        assertEquals(PusherEncryption("aes-hmac-sha256", "2026-07", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "f47ac10b-58cc-4372-a567-0e02b2c3d479"), encryption)
    }

    @Test
    fun setPusher_postsRegistrationWithEncryptionObjectToPushersSet() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)

        val pusher = Pusher(
            pushkey = "<APNS/GCM TOKEN>",
            kind = "http",
            appId = "de.gematik.fdv.ios",
            appDisplayName = "FdV",
            deviceDisplayName = "iPhone 9",
            lang = "en",
            data = PusherData(url = "https://push-gateway.example/push/v1/"),
            encryption = PusherEncryption(
                method = "aes-hmac-sha256",
                timeIssCreated = "2026-07",
                iss = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                keyIdentifier = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            ),
        )

        client.setPusher(pusher)

        assertEquals(HttpMethod.Post, seenRequest?.method)
        assertEquals("/pushers/set", seenRequest?.url?.encodedPath)
        val body = (seenRequest?.body as TextContent).text
        assertTrue(body.contains("\"key_identifier\":\"f47ac10b-58cc-4372-a567-0e02b2c3d479\""))
        assertTrue(body.contains("\"app_id\":\"de.gematik.fdv.ios\""))
    }

    @Test
    fun setPusher_withNullKindSendsDeletionPayload() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)

        client.setPusher(Pusher(pushkey = "<APNS/GCM TOKEN>", kind = null, appId = "de.gematik.fdv.ios"))

        val body = (seenRequest?.body as TextContent).text
        assertTrue(body.contains("\"kind\":null"))
    }
}
