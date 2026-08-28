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

/**
 * Verifies the token-provider seam end to end: a call through [NotificationApiClientImpl] must
 * carry whatever token the active [NotificationTokenProvider] hands out, without the client
 * knowing which implementation that is.
 */
class NotificationApiClientImplTest {
    @Test
    fun getPushers_carriesTokenInjectedByStaticProvider() = runTest {
        val injectedToken = "static-idp-simulator-token"
        var seenAuthorizationHeader: String? = null

        val engine = MockEngine { request ->
            seenAuthorizationHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"pushers":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, StaticNotificationTokenProvider(injectedToken), testDpopProvider)

        client.getPushers()

        assertEquals("dpop $injectedToken", seenAuthorizationHeader)
    }

    @Test
    fun getChannels_worksUnchangedWithAnyTokenProviderImplementation() = runTest {
        val simulatedGuardToken = "guard-flow-simulated-token"
        var seenAuthorizationHeader: String? = null

        val engine = MockEngine { request ->
            seenAuthorizationHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"channels":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val httpClient = ZetaHttpClientBuilder(TEST_SERVICE_BASE_URL).build(engine)
        // A provider implementation distinct from StaticNotificationTokenProvider and
        // ReauthNotificationTokenProvider, standing in for a future swap. NotificationApiClientImpl
        // needs no changes to work with it — only the NotificationTokenProvider seam is used.
        val alternativeProvider = NotificationTokenProvider { simulatedGuardToken }
        val client = NotificationApiClientImpl(httpClient, TEST_SERVICE_BASE_URL, alternativeProvider, testDpopProvider)

        client.getChannels()

        assertEquals("dpop $simulatedGuardToken", seenAuthorizationHeader)
    }
}
