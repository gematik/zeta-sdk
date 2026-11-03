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
import de.gematik.zeta.sdk.notifications.model.HistoricNotification
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `getHistoricNotification(id)`/`getHistoricNotifications(pushkey)` against the
 * `message_get.yaml`/`messages_get.yaml` contract, and the history-only `404`/`501` mapping (A_29974).
 */
class NotificationApiClientImplHistoryTest {
    private var seenRequest: HttpRequestData? = null

    private fun clientRespondingWith(
        status: HttpStatusCode,
        body: String,
        contentType: ContentType = ContentType.Application.Json,
    ): NotificationApiClientImpl {
        val engine = MockEngine { request ->
            seenRequest = request
            respond(body, status, headersOf(HttpHeaders.ContentType, contentType.toString()))
        }
        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        return NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)
    }

    @Test
    fun getHistoricNotification_callsHistoryWithUrlEncodedIdAndReturnsPayloadAsReceived() = runTest {
        val client = clientRespondingWith(
            HttpStatusCode.OK,
            """{"identifier":"a/b","payload":{"event":"new_message","count":2}}""",
        )

        val notification = client.getHistoricNotification("a/b")

        assertEquals(HttpMethod.Get, seenRequest?.method)
        val requestedPath = seenRequest?.url?.encodedPath.orEmpty()
        assertTrue(requestedPath.startsWith("/history/"))
        assertEquals("a/b", requestedPath.removePrefix("/history/").decodeURLPart())
        val payload = buildJsonObject {
            put("event", "new_message")
            put("count", 2)
        }
        assertEquals(HistoricNotification("a/b", payload), notification)
    }

    @Test
    fun getHistoricNotifications_callsHistoryOfDeviceWithUrlEncodedPushkey() = runTest {
        val client = clientRespondingWith(
            HttpStatusCode.OK,
            """{"messages":[{"identifier":"id-1","payload":{"event":"a"}},{"identifier":"id-2","payload":{"event":"b"}}]}""",
        )

        val pushkey = "Xp/MzCt8/9DcSNE9cuiaoT5Ac55job3TdLSSmtmYl4A="
        val notifications = client.getHistoricNotifications(pushkey)

        assertEquals(HttpMethod.Get, seenRequest?.method)
        val requestedPath = seenRequest?.url?.encodedPath.orEmpty()
        assertTrue(requestedPath.startsWith("/history/device/"))
        assertEquals(pushkey, requestedPath.removePrefix("/history/device/").decodeURLPart())
        assertEquals(listOf("id-1", "id-2"), notifications.map { it.identifier })
    }

    @Test
    fun getHistoricNotifications_returnsEmptyListForEmptyHistory() = runTest {
        val client = clientRespondingWith(HttpStatusCode.OK, """{"messages":[]}""")

        assertEquals(emptyList(), client.getHistoricNotifications("device-1"))
    }

    @Test
    fun getHistoricNotifications_keepsItemWithoutPayloadAsEmptyPayload() = runTest {
        val client = clientRespondingWith(HttpStatusCode.OK, """{"messages":[{"identifier":"id-1"}]}""")

        assertEquals(listOf(HistoricNotification("id-1", JsonObject(emptyMap()))), client.getHistoricNotifications("device-1"))
    }

    @Test
    fun getHistoricNotifications_skipsMalformedItemsAndKeepsTheRest() = runTest {
        val client = clientRespondingWith(
            HttpStatusCode.OK,
            """{"messages":[{"payload":{"event":"no-id"}},{"identifier":"id-1","payload":"not-an-object"},null,{"identifier":"id-2"}]}""",
        )

        assertEquals(listOf("id-2"), client.getHistoricNotifications("device-1").map { it.identifier })
    }

    @Test
    fun getHistoricNotifications_failsWhenMessagesFieldIsMissing() = runTest {
        // A renamed/missing list field must not be mistaken for an empty history.
        val client = clientRespondingWith(HttpStatusCode.OK, """{"notifications":[{"identifier":"id-1"}]}""")

        assertFails { client.getHistoricNotifications("device-1") }
    }

    @Test
    fun notFound_mapsToNotificationHistoryNotFoundExceptionWithErrorCode() = runTest {
        val client = clientRespondingWith(
            HttpStatusCode.NotFound,
            """{"errorCode":"NOTIFICATION_NOT_FOUND","errorDetail":"No notification is stored for the authenticated user"}""",
        )

        val exception = assertFailsWith<NotificationHistoryNotFoundException> { client.getHistoricNotification("id-1") }

        assertEquals("NOTIFICATION_NOT_FOUND", exception.errorCode)
        assertEquals("No notification is stored for the authenticated user", exception.errorDetail)
    }

    @Test
    fun notFoundForUnknownPushkey_mapsToNotificationHistoryNotFoundException() = runTest {
        val client = clientRespondingWith(HttpStatusCode.NotFound, """{"errorCode":"PUSHER_NOT_FOUND"}""")

        val exception = assertFailsWith<NotificationHistoryNotFoundException> { client.getHistoricNotifications("device-1") }

        assertEquals("PUSHER_NOT_FOUND", exception.errorCode)
        assertNull(exception.errorDetail)
    }

    @Test
    fun notFoundWithoutJsonBody_mapsToNotificationHistoryNotFoundExceptionWithoutErrorCode() = runTest {
        // e.g. a proxy in front of the Notification Service answering with an HTML page.
        val client = clientRespondingWith(HttpStatusCode.NotFound, "<html>Not Found</html>", ContentType.Text.Html)

        val exception = assertFailsWith<NotificationHistoryNotFoundException> { client.getHistoricNotifications("device-1") }

        assertNull(exception.errorCode)
        assertEquals(HttpStatusCode.NotFound, exception.response.status)
    }

    @Test
    fun notImplemented_mapsToNotificationHistoryNotFoundException() = runTest {
        val client = clientRespondingWith(HttpStatusCode.NotImplemented, "")

        val exception = assertFailsWith<NotificationHistoryNotFoundException> { client.getHistoricNotification("id-1") }

        assertEquals(HttpStatusCode.NotImplemented, exception.response.status)
    }

    @Test
    fun forbidden_staysNotificationApiForbiddenException() = runTest {
        val client = clientRespondingWith(HttpStatusCode.Forbidden, "")

        assertFailsWith<NotificationApiForbiddenException> { client.getHistoricNotifications("device-1") }
    }

    @Test
    fun serverError_staysUnexpectedNotificationApiException() = runTest {
        val client = clientRespondingWith(HttpStatusCode.InternalServerError, "")

        assertFailsWith<UnexpectedNotificationApiException> { client.getHistoricNotification("id-1") }
    }
}
