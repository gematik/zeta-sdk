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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The NS API is reached behind the PEP under a base-path prefix (e.g. `/push/v1`). The client must
 * request the absolute URL derived from [serviceBaseUrl]; a leading-slash relative path would let
 * Ktor's base URL replace the whole path and drop the prefix, and would desync the DPoP `htu` from
 * the URL actually requested (A_29977).
 */
class NotificationApiClientImplBasePathTest {
    private val prefixedBaseUrl = "http://notification-service.example/push/v1"

    @Test
    fun get_preservesBasePathPrefix_andBindsProofToSameUrl() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond(
                content = """{ "pushers": [] }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val proofHtu = mutableListOf<String>()
        val dpopProvider = NotificationDpopProvider { _, htu, _ -> proofHtu += htu; "test-dpop-proof" }
        val client = NotificationApiClientImpl(
            ZetaHttpClientBuilder(prefixedBaseUrl).build(engine),
            prefixedBaseUrl,
            StaticNotificationTokenProvider("token"),
            dpopProvider,
        )

        client.getPushers()

        assertEquals("/push/v1/pushers", seenRequest?.url?.encodedPath)
        assertEquals(listOf("$prefixedBaseUrl/pushers"), proofHtu)
    }

    @Test
    fun get_trimsTrailingSlashOnServiceBaseUrl() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond(
                content = """{ "pushers": [] }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = NotificationApiClientImpl(
            ZetaHttpClientBuilder(prefixedBaseUrl).build(engine),
            "$prefixedBaseUrl/",
            StaticNotificationTokenProvider("token"),
            testDpopProvider,
        )

        client.getPushers()

        assertEquals("/push/v1/pushers", seenRequest?.url?.encodedPath)
    }

    @Test
    fun post_preservesBasePathPrefix() = runTest {
        var seenRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            seenRequest = request
            respond("", HttpStatusCode.OK)
        }
        val client = NotificationApiClientImpl(
            ZetaHttpClientBuilder(prefixedBaseUrl).build(engine),
            prefixedBaseUrl,
            StaticNotificationTokenProvider("token"),
            testDpopProvider,
        )

        client.setPusher(Pusher(appId = "de.gematik.fdv", pushkey = "k", kind = "http"))

        assertEquals("/push/v1/pushers/set", seenRequest?.url?.encodedPath)
    }
}
