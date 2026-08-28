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

import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the bounded automatic retry on `429 Too Many Requests` (A_25339: exponential backoff,
 * A_27007: honor the server's waiting-time hint). Wait times are asserted against `runTest`'s
 * virtual clock, so the tests run instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationApiClientImplRateLimitRetryTest {
    private class CountingDpopProvider : NotificationDpopProvider {
        var proofsIssued = 0

        override suspend fun createDpopProof(htm: String, htu: String, accessToken: String): String {
            proofsIssued++
            return "proof-$proofsIssued"
        }
    }

    /** Responds 429 [rateLimitedTimes] times (with [rateLimitBody]), then 200. */
    private class Fixture(
        rateLimitedTimes: Int,
        rateLimitBody: String = """{"errorCode":"M_LIMIT_EXCEEDED","errorDetail":"Too many requests","retry_after_ms":2000}""",
        policy: RateLimitRetryPolicy = RateLimitRetryPolicy(),
    ) {
        var attempts = 0
        var lastSeenProofHeader: String? = null
        val dpopProvider = CountingDpopProvider()
        val client: NotificationApiClientImpl

        init {
            val engine = MockEngine { request ->
                attempts++
                lastSeenProofHeader = request.headers[HttpAuthHeaders.Dpop]
                if (attempts <= rateLimitedTimes) {
                    respond(rateLimitBody, HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                } else {
                    respond("""{"channels":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            }
            val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
            client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("token"), dpopProvider, policy)
        }
    }

    @Test
    fun rateLimitedOnce_retriesAndSucceeds() = runTest {
        val fixture = Fixture(rateLimitedTimes = 1)

        val channels = fixture.client.getChannels()

        assertEquals(emptyList(), channels)
        assertEquals(2, fixture.attempts)
    }

    @Test
    fun everyRetryAttemptIsSignedWithAFreshDpopProof() = runTest {
        val fixture = Fixture(rateLimitedTimes = 2)

        fixture.client.getChannels()

        assertEquals(3, fixture.dpopProvider.proofsIssued)
        assertEquals("proof-3", fixture.lastSeenProofHeader)
    }

    @Test
    fun waitsAtLeastTheServerProvidedRetryAfter() = runTest {
        // retry_after_ms = 2000 dominates the 500ms initial backoff
        val fixture = Fixture(rateLimitedTimes = 1)

        fixture.client.getChannels()

        assertEquals(2000, currentTime)
    }

    @Test
    fun fallsBackToExponentialBackoffWhenRetryAfterIsMissing() = runTest {
        val fixture = Fixture(
            rateLimitedTimes = 2,
            rateLimitBody = """{"errorCode":"M_LIMIT_EXCEEDED","errorDetail":"Too many requests"}""",
        )

        fixture.client.getChannels()

        // 500ms after the first 429, doubled to 1000ms after the second
        assertEquals(1500, currentTime)
    }

    @Test
    fun exhaustedRetryBudget_rethrowsTheRateLimitForTheHostAppToHandle() = runTest {
        val fixture = Fixture(rateLimitedTimes = Int.MAX_VALUE, policy = RateLimitRetryPolicy(maxRetries = 2))

        val exception = assertFailsWith<NotificationRateLimitedException> { fixture.client.getChannels() }

        assertEquals(3, fixture.attempts)
        assertEquals(2000L, exception.retryAfterMs)
    }

    @Test
    fun zeroMaxRetries_disablesAutomaticRetry() = runTest {
        val fixture = Fixture(rateLimitedTimes = Int.MAX_VALUE, policy = RateLimitRetryPolicy(maxRetries = 0))

        assertFailsWith<NotificationRateLimitedException> { fixture.client.getChannels() }

        assertEquals(1, fixture.attempts)
        assertEquals(0, currentTime)
    }
}
