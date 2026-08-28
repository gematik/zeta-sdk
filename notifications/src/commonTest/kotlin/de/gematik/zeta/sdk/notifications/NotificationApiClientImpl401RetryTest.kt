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
 * Covers the single invalidate-and-retry on `401 Unauthorized` (A_29975: e.g. the service
 * token was revoked server-side). The cached token is dropped once and the whole operation —
 * token acquisition, proof, request — re-runs exactly once; `403` is never retried.
 */
class NotificationApiClientImpl401RetryTest {
    private class RecordingTokenProvider : NotificationTokenProvider {
        var tokensIssued = 0
        var invalidateCalls = 0

        override suspend fun getAccessToken(requiredScopes: Set<String>): String {
            tokensIssued++
            return "token-$tokensIssued"
        }

        override suspend fun invalidate(requiredScopes: Set<String>) {
            invalidateCalls++
        }
    }

    /** Responds with [failStatus] [failingTimes] times, then 200. */
    private class Fixture(
        failingTimes: Int,
        failStatus: HttpStatusCode = HttpStatusCode.Unauthorized,
    ) {
        var attempts = 0
        var lastSeenAuthorizationHeader: String? = null
        val tokenProvider = RecordingTokenProvider()
        val client: NotificationApiClientImpl

        init {
            val engine = MockEngine { request ->
                attempts++
                lastSeenAuthorizationHeader = request.headers[HttpHeaders.Authorization]
                if (attempts <= failingTimes) {
                    respond("""{}""", failStatus, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                } else {
                    respond("""{"channels":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            }
            val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
            client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, tokenProvider, testDpopProvider)
        }
    }

    @Test
    fun unauthorizedOnce_invalidatesAndRetries_thenSucceeds() = runTest {
        val fixture = Fixture(failingTimes = 1)

        val channels = fixture.client.getChannels()

        assertEquals(emptyList(), channels)
        assertEquals(2, fixture.attempts)
        assertEquals(1, fixture.tokenProvider.invalidateCalls)
    }

    @Test
    fun retryCarriesAFreshlyAcquiredToken() = runTest {
        val fixture = Fixture(failingTimes = 1)

        fixture.client.getChannels()

        assertEquals(2, fixture.tokenProvider.tokensIssued)
        assertEquals("dpop token-2", fixture.lastSeenAuthorizationHeader)
    }

    @Test
    fun unauthorizedTwice_surfacesException_afterExactlyOneRetry() = runTest {
        val fixture = Fixture(failingTimes = 2)

        assertFailsWith<NotificationApiUnauthorizedException> { fixture.client.getChannels() }

        assertEquals(2, fixture.attempts)
        assertEquals(1, fixture.tokenProvider.invalidateCalls)
    }

    @Test
    fun forbidden_isNeverRetried() = runTest {
        val fixture = Fixture(failingTimes = 1, failStatus = HttpStatusCode.Forbidden)

        assertFailsWith<NotificationApiForbiddenException> { fixture.client.getChannels() }

        assertEquals(1, fixture.attempts)
        assertEquals(0, fixture.tokenProvider.invalidateCalls)
    }
}
