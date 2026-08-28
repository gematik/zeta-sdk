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
 * Verifies the DPoP request signing per RFC 9449 / A_29975–A_29978: the access token travels in
 * `Authorization: dpop <token>` (never `Bearer`), every request carries a proof JWT in the
 * `dpop` header, and each proof is freshly created and bound to exactly the HTTP method and
 * absolute URL of the request it accompanies.
 */
class NotificationApiClientImplDpopTest {
    private class RecordingDpopProvider : NotificationDpopProvider {
        data class ProofRequest(val htm: String, val htu: String, val accessToken: String)

        val proofRequests = mutableListOf<ProofRequest>()

        override suspend fun createDpopProof(htm: String, htu: String, accessToken: String): String {
            proofRequests += ProofRequest(htm, htu, accessToken)
            return "proof-${proofRequests.size}"
        }
    }

    private class Fixture(responseBody: String) {
        var seenRequest: HttpRequestData? = null
        val dpopProvider = RecordingDpopProvider()
        val client: NotificationApiClientImpl

        init {
            val engine = MockEngine { request ->
                seenRequest = request
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
            client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider("access-token"), dpopProvider)
        }
    }

    @Test
    fun get_sendsDpopAuthorizationSchemeAndProofHeader() = runTest {
        val fixture = Fixture("""{"pushers":[]}""")

        fixture.client.getPushers()

        assertEquals("dpop access-token", fixture.seenRequest?.headers[HttpHeaders.Authorization])
        assertEquals("proof-1", fixture.seenRequest?.headers[HttpAuthHeaders.Dpop])
    }

    @Test
    fun get_bindsProofToMethodUrlAndAccessToken() = runTest {
        val fixture = Fixture("""{"pushers":[]}""")

        fixture.client.getPushers()

        val proofRequest = fixture.dpopProvider.proofRequests.single()
        assertEquals("GET", proofRequest.htm)
        assertEquals("$TEST_SERVICE_BASE_URL/pushers", proofRequest.htu)
        assertEquals("access-token", proofRequest.accessToken)
    }

    @Test
    fun post_bindsProofToMethodAndUrl() = runTest {
        val fixture = Fixture("{}")

        fixture.client.setPusher(Pusher(pushkey = "key", kind = "http", appId = "app"))

        val proofRequest = fixture.dpopProvider.proofRequests.single()
        assertEquals("POST", proofRequest.htm)
        assertEquals("$TEST_SERVICE_BASE_URL/pushers/set", proofRequest.htu)
        assertEquals("proof-1", fixture.seenRequest?.headers[HttpAuthHeaders.Dpop])
    }

    @Test
    fun getChannel_htuMatchesTheEncodedUrlActuallyRequested() = runTest {
        val fixture = Fixture("""{"channels":[]}""")

        // pushkey requiring path-segment encoding (base64 tokens use '/', '=')
        fixture.client.getChannel("Xp/MzCt8/9DcSNE9cuiaoT5Ac55job3TdLSSmtmYl4A=")

        val requestedUrl = TEST_SERVICE_BASE_URL + fixture.seenRequest?.url?.encodedPath
        assertEquals(requestedUrl, fixture.dpopProvider.proofRequests.single().htu)
    }

    @Test
    fun everyRequestCarriesAFreshProof() = runTest {
        val fixture = Fixture("""{"channels":[]}""")

        fixture.client.getChannels()
        fixture.client.getChannels()

        assertEquals(2, fixture.dpopProvider.proofRequests.size)
        assertEquals("proof-2", fixture.seenRequest?.headers[HttpAuthHeaders.Dpop])
    }
}
