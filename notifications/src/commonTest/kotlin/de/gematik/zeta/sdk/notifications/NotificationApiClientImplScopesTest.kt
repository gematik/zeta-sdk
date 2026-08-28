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
import de.gematik.zeta.sdk.notifications.model.Pusher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asserts that every endpoint passes exactly its least-privilege scope (A_29979) into the
 * [NotificationTokenProvider] seam — the contract the future per-scope-token mapping relies on.
 */
class NotificationApiClientImplScopesTest {
    private class ScopeRecordingProvider : NotificationTokenProvider {
        val scopesSeen = mutableListOf<Set<String>>()

        override suspend fun getAccessToken(requiredScopes: Set<String>): String {
            scopesSeen.add(requiredScopes)
            return "token"
        }
    }

    private class Fixture {
        val tokenProvider = ScopeRecordingProvider()
        val client: NotificationApiClientImpl

        init {
            val engine = MockEngine { request ->
                val body = if (request.url.encodedPath.contains("/pushers")) """{"pushers":[]}""" else """{"channels":[]}"""
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
            client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, tokenProvider, testDpopProvider)
        }
    }

    @Test
    fun getPushers_requestsPusherReadScope() = runTest {
        val fixture = Fixture()

        fixture.client.getPushers()

        assertEquals(listOf(setOf(NotificationScopes.PUSHER_READ)), fixture.tokenProvider.scopesSeen)
    }

    @Test
    fun setPusher_requestsPusherWriteScope() = runTest {
        val fixture = Fixture()

        fixture.client.setPusher(Pusher(pushkey = "key", kind = "http", appId = "app"))

        assertEquals(listOf(setOf(NotificationScopes.PUSHER_WRITE)), fixture.tokenProvider.scopesSeen)
    }

    @Test
    fun getChannels_requestsChannelReadScope() = runTest {
        val fixture = Fixture()

        fixture.client.getChannels()

        assertEquals(listOf(setOf(NotificationScopes.CHANNEL_READ)), fixture.tokenProvider.scopesSeen)
    }

    @Test
    fun getChannel_requestsChannelReadScope() = runTest {
        val fixture = Fixture()

        fixture.client.getChannel("pushkey")

        assertEquals(listOf(setOf(NotificationScopes.CHANNEL_READ)), fixture.tokenProvider.scopesSeen)
    }

    @Test
    fun setChannel_requestsChannelWriteScope() = runTest {
        val fixture = Fixture()

        fixture.client.setChannel("pushkey", listOf(Channel("chat", ChannelStatus.ENABLED)))

        assertEquals(listOf(setOf(NotificationScopes.CHANNEL_WRITE)), fixture.tokenProvider.scopesSeen)
    }
}
