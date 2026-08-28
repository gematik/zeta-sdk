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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies NS error responses (4xx/5xx) are translated into typed SDK exceptions without altering
 * the Notification Service's status codes/error semantics.
 */
class NotificationApiClientImplErrorMappingTest {
    private fun clientRespondingWith(status: HttpStatusCode, body: String): NotificationApiClientImpl {
        val engine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        return NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), testDpopProvider)
    }

    @Test
    fun badRequest_mapsToInvalidNotificationRequestExceptionWithErrorCodeAndDetail() = runTest {
        val client = clientRespondingWith(
            HttpStatusCode.BadRequest,
            """{"errorCode":"M_MISSING_PARAM","errorDetail":"Missing parameters: lang, data"}""",
        )

        val exception = assertFailsWith<InvalidNotificationRequestException> { client.getPushers() }

        assertEquals("M_MISSING_PARAM", exception.errorCode)
        assertEquals("Missing parameters: lang, data", exception.errorDetail)
    }

    @Test
    fun tooManyRequests_mapsToNotificationRateLimitedExceptionWithRetryAfter() = runTest {
        val client = clientRespondingWith(
            HttpStatusCode.TooManyRequests,
            """{"errorCode":"M_LIMIT_EXCEEDED","errorDetail":"Too many requests","retry_after_ms":2000}""",
        )

        val exception = assertFailsWith<NotificationRateLimitedException> { client.getChannels() }

        assertEquals("M_LIMIT_EXCEEDED", exception.errorCode)
        assertEquals(2000L, exception.retryAfterMs)
    }

    @Test
    fun unauthorized_mapsToNotificationApiUnauthorizedException() = runTest {
        val client = clientRespondingWith(HttpStatusCode.Unauthorized, "")

        assertFailsWith<NotificationApiUnauthorizedException> { client.getPushers() }
    }

    @Test
    fun forbidden_mapsToNotificationApiForbiddenException() = runTest {
        val client = clientRespondingWith(HttpStatusCode.Forbidden, "")

        assertFailsWith<NotificationApiForbiddenException> { client.getChannels() }
    }

    @Test
    fun serverError_mapsToUnexpectedNotificationApiExceptionWithoutReinterpretation() = runTest {
        val client = clientRespondingWith(HttpStatusCode.InternalServerError, "")

        val exception = assertFailsWith<UnexpectedNotificationApiException> { client.getPushers() }

        assertEquals(HttpStatusCode.InternalServerError, exception.response.status)
    }

    @Test
    fun badRequest_withoutErrorDetail_mapsWithNullDetail() = runTest {
        val client = clientRespondingWith(HttpStatusCode.BadRequest, """{"errorCode":"M_MISSING_PARAM"}""")

        val exception = assertFailsWith<InvalidNotificationRequestException> { client.getPushers() }

        assertEquals("M_MISSING_PARAM", exception.errorCode)
        assertEquals(null, exception.errorDetail)
    }

    @Test
    fun tooManyRequests_withoutRetryAfter_mapsWithNullRetry() = runTest {
        val client = clientRespondingWith(HttpStatusCode.TooManyRequests, """{"errorCode":"M_LIMIT_EXCEEDED"}""")

        val exception = assertFailsWith<NotificationRateLimitedException> { client.getChannels() }

        assertEquals("M_LIMIT_EXCEEDED", exception.errorCode)
        assertEquals(null, exception.retryAfterMs)
        assertEquals(null, exception.errorDetail)
    }
}
