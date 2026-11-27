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

package de.gematik.zeta.driver.core.popp

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoppTokenProviderTests {
    private val config = PoppGeneratorConfig(
        generatorUrl = "https://gen.example/token-generator",
        proofMethod = "ehc-practitioner-user-x509",
        patientId = "X110639491",
        insurerId = "109500969",
    )
    private val actor = PoppActor("1-20000300139", "1.2.276.0.76.4.50")
    private val json = ContentType.Application.Json.toString()

    private class Fixture(val provider: PoppTokenProvider, val requests: MutableList<HttpRequestData>, val bodies: MutableList<String>)

    private fun fixture(handler: suspend (Int) -> Pair<HttpStatusCode, String>): Fixture {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request
            bodies += request.body.toByteArray().decodeToString()
            val (status, content) = handler(requests.size)
            respond(content, status, headersOf(HttpHeaders.ContentType, json))
        }
        return Fixture(PoppTokenProvider(ZetaHttpClient(HttpClient(engine))) { 1793880514L }, requests, bodies)
    }

    private fun ok(token: String) = HttpStatusCode.OK to """{"tokenResults":["$token"]}"""

    @Test
    fun tokenFor_postsTicketBody_andReturnsFirstToken() = runTest {
        val f = fixture { HttpStatusCode.OK to """{"tokenResults":["jwt-1","jwt-2"]}""" }

        assertEquals("jwt-1", f.provider.tokenFor(config) { actor })

        val request = f.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://gen.example/token-generator", request.url.toString())
        assertEquals("*/*", request.headers[HttpHeaders.Accept])
        assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
        assertEquals(
            Json.parseToJsonElement(
                """{"tokenParamsList":[{"proofMethod":"ehc-practitioner-user-x509","patientProofTime":"1793880514","iat":"1793880514","patientId":"X110639491","insurerId":"109500969","actorId":"1-20000300139","actorProfessionOid":"1.2.276.0.76.4.50"}]}""",
            ),
            Json.parseToJsonElement(f.bodies.single()),
        )
    }

    @Test
    fun tokenFor_returnsNull_withoutRequest_whenUrlIsBlank() = runTest {
        val f = fixture { ok("jwt") }
        var actorRead = false

        assertNull(f.provider.tokenFor(config.copy(generatorUrl = "  ")) { actorRead = true; actor })

        assertEquals(0, f.requests.size)
        assertEquals(false, actorRead)
    }

    @Test
    fun tokenFor_usesDefaults_whenParametersMissing() = runTest {
        val f = fixture { ok("jwt") }

        val token = f.provider.tokenFor(config.copy(proofMethod = "", patientId = " ", insurerId = "")) { actor }

        assertEquals("jwt", token)
        val params = Json.parseToJsonElement(f.bodies.single()).toString()
        assertEquals(true, "\"proofMethod\":\"$DEFAULT_POPP_PROOF_METHOD\"" in params)
        assertEquals(true, "\"patientId\":\"$DEFAULT_POPP_PATIENT_ID\"" in params)
        assertEquals(true, "\"insurerId\":\"$DEFAULT_POPP_INSURER_ID\"" in params)
    }

    @Test
    fun tokenFor_returnsNull_whenReadActorThrows() = runTest {
        val f = fixture { ok("jwt") }

        assertNull(f.provider.tokenFor(config) { error("bad p12") })

        assertEquals(0, f.requests.size)
    }

    @Test
    fun tokenFor_returnsNull_whenGeneratorReturns400() = runTest {
        val f = fixture { HttpStatusCode.BadRequest to "" }

        assertNull(f.provider.tokenFor(config) { actor })
        assertEquals(1, f.requests.size)
    }

    @Test
    fun tokenFor_returnsNull_whenTokenResultsEmpty() = runTest {
        val f = fixture { HttpStatusCode.OK to """{"tokenResults":[]}""" }

        assertNull(f.provider.tokenFor(config) { actor })
    }

    @Test
    fun tokenFor_returnsNull_whenTokenIsBlank() = runTest {
        val f = fixture { HttpStatusCode.OK to """{"tokenResults":["  "]}""" }

        assertNull(f.provider.tokenFor(config) { actor })
    }

    @Test
    fun tokenFor_returnsNull_whenBodyIsNotJson() = runTest {
        val f = fixture { HttpStatusCode.OK to "<html>proxy</html>" }

        assertNull(f.provider.tokenFor(config) { actor })
    }

    @Test
    fun tokenFor_returnsNull_whenRequestTimesOut() = runTest {
        val engine = MockEngine { throw HttpRequestTimeoutException("u", 10_000) }
        val provider = PoppTokenProvider(ZetaHttpClient(HttpClient(engine))) { 1793880514L }

        assertNull(provider.tokenFor(config) { actor })
    }

    @Test
    fun tokenFor_callsGeneratorOncePerActor() = runTest {
        val f = fixture { n -> ok("jwt-$n") }
        val actor2 = PoppActor("1-99", "1.2.276.0.76.4.50")

        assertEquals("jwt-1", f.provider.tokenFor(config) { actor })
        assertEquals("jwt-1", f.provider.tokenFor(config) { actor })
        assertEquals(1, f.requests.size)

        assertEquals("jwt-2", f.provider.tokenFor(config) { actor2 })
        assertEquals(2, f.requests.size)
    }

    @Test
    fun tokenFor_doesNotRetry_afterFailure() = runTest {
        val f = fixture { HttpStatusCode.BadRequest to "" }

        assertNull(f.provider.tokenFor(config) { actor })
        assertNull(f.provider.tokenFor(config) { actor })

        assertEquals(1, f.requests.size)
    }
}
