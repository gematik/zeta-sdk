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

package de.gematik.zeta.sdk.network.http.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpClientSyncTest {
    data class CapturedRequest(
        val method: String,
        val url: String,
        val headers: Headers,
        val body: String,
        val contentType: ContentType?,
    )

    private val captured = mutableListOf<CapturedRequest>()

    @BeforeTest
    fun setUp() { captured.clear() }

    private val last get() = captured.last()

    private val testUrl = "https://api.example.com/test"
    private val testHeaders = mapOf("Authorization" to "Bearer token", "X-Custom" to "value")
    private val testBody = """{"key":"value"}"""
    private val responseBody = """{"status":"ok"}"""
    private val responseHeaders = headersOf(
        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
        "X-Response-Header" to listOf("response-value"),
    )

    private fun buildClient(
        body: String = responseBody,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        val engine = MockEngine { request ->
            captured += CapturedRequest(
                method = request.method.value,
                url = request.url.toString(),
                headers = request.headers,
                body = request.body.toByteArray().decodeToString(),
                contentType = request.body.contentType,
            )
            respond(body, status, responseHeaders)
        }
        return HttpClient(engine)
    }

    @Test
    fun get_usesGetMethod() {
        with(HttpClientSync) { buildClient().get(testUrl, testHeaders) }
        assertEquals("GET", last.method)
    }

    @Test
    fun get_usesCorrectUrl() {
        with(HttpClientSync) { buildClient().get(testUrl, testHeaders) }
        assertTrue(last.url.contains("api.example.com/test"))
    }

    @Test
    fun get_forwardsAllHeaders() {
        with(HttpClientSync) { buildClient().get(testUrl, testHeaders) }
        assertEquals("Bearer token", last.headers["Authorization"])
        assertEquals("value", last.headers["X-Custom"])
    }

    @Test
    fun get_returnsCorrectStatus() {
        val result = with(HttpClientSync) { buildClient(status = HttpStatusCode.OK).get(testUrl, testHeaders) }
        assertEquals(200, result.status)
    }

    @Test
    fun get_returnsResponseBody() {
        val result = with(HttpClientSync) { buildClient(body = responseBody).get(testUrl, testHeaders) }
        assertEquals(responseBody, result.body)
    }

    @Test
    fun get_returnsResponseHeaders() {
        val result = with(HttpClientSync) { buildClient().get(testUrl, testHeaders) }
        assertTrue(result.headers.containsKey(HttpHeaders.ContentType))
    }

    @Test
    fun get_withEmptyHeaders_sendsRequest() {
        with(HttpClientSync) { buildClient().get(testUrl, emptyMap()) }
        assertEquals("GET", last.method)
    }

    @Test
    fun post_usesPostMethod() {
        with(HttpClientSync) { buildClient().post(testUrl, testBody, testHeaders) }
        assertEquals("POST", last.method)
    }

    @Test
    fun post_usesCorrectUrl() {
        with(HttpClientSync) { buildClient().post(testUrl, testBody, testHeaders) }
        assertTrue(last.url.contains("api.example.com/test"))
    }

    @Test
    fun post_setsJsonContentType() {
        with(HttpClientSync) { buildClient().post(testUrl, testBody, testHeaders) }
        assertEquals(ContentType.Application.Json, last.contentType?.withoutParameters())
    }

    @Test
    fun post_forwardsBody() {
        with(HttpClientSync) { buildClient().post(testUrl, testBody, testHeaders) }
        assertEquals(testBody, last.body)
    }

    @Test
    fun post_forwardsHeaders() {
        with(HttpClientSync) { buildClient().post(testUrl, testBody, testHeaders) }
        assertEquals("Bearer token", last.headers["Authorization"])
    }

    @Test
    fun post_returnsCorrectStatus() {
        val result = with(HttpClientSync) { buildClient(status = HttpStatusCode.Created).post(testUrl, testBody, testHeaders) }
        assertEquals(201, result.status)
    }

    @Test
    fun post_returnsResponseBody() {
        val result = with(HttpClientSync) { buildClient(body = responseBody).post(testUrl, testBody, testHeaders) }
        assertEquals(responseBody, result.body)
    }

    @Test
    fun put_usesPutMethod() {
        with(HttpClientSync) { buildClient().put(testUrl, testHeaders) }
        assertEquals("PUT", last.method)
    }

    @Test
    fun put_forwardsHeaders() {
        with(HttpClientSync) { buildClient().put(testUrl, testHeaders) }
        assertEquals("Bearer token", last.headers["Authorization"])
    }

    @Test
    fun put_returnsCorrectStatus() {
        val result = with(HttpClientSync) { buildClient(status = HttpStatusCode.OK).put(testUrl, testHeaders) }
        assertEquals(200, result.status)
    }

    @Test
    fun patch_usesPatchMethod() {
        with(HttpClientSync) { buildClient().patch(testUrl, testHeaders) }
        assertEquals("PATCH", last.method)
    }

    @Test
    fun patch_forwardsHeaders() {
        with(HttpClientSync) { buildClient().patch(testUrl, testHeaders) }
        assertEquals("Bearer token", last.headers["Authorization"])
    }

    @Test
    fun patch_returnsCorrectStatus() {
        val result = with(HttpClientSync) { buildClient(status = HttpStatusCode.OK).patch(testUrl, testHeaders) }
        assertEquals(200, result.status)
    }

    @Test
    fun options_usesOptionsMethod() {
        with(HttpClientSync) { buildClient().options(testUrl, testHeaders) }
        assertEquals("OPTIONS", last.method)
    }

    @Test
    fun options_forwardsHeaders() {
        with(HttpClientSync) { buildClient().options(testUrl, testHeaders) }
        assertEquals("Bearer token", last.headers["Authorization"])
    }

    @Test
    fun head_usesHeadMethod() {
        with(HttpClientSync) { buildClient().head(testUrl, testHeaders) }
        assertEquals("HEAD", last.method)
    }

    @Test
    fun head_forwardsHeaders() {
        with(HttpClientSync) { buildClient().head(testUrl, testHeaders) }
        assertEquals("Bearer token", last.headers["Authorization"])
    }

    @Test
    fun delete_usesDeleteMethod() {
        with(HttpClientSync) { buildClient().delete(testUrl, testHeaders) }
        assertEquals("DELETE", last.method)
    }

    @Test
    fun delete_forwardsHeaders() {
        with(HttpClientSync) { buildClient().delete(testUrl, testHeaders) }
        assertEquals("Bearer token", last.headers["Authorization"])
    }

    @Test
    fun delete_returnsCorrectStatus() {
        val result = with(HttpClientSync) { buildClient(status = HttpStatusCode.NoContent).delete(testUrl, testHeaders) }
        assertEquals(204, result.status)
    }

    @Test
    fun httpResponseWrapper_statusMatchesHttpStatus() {
        val result = with(HttpClientSync) { buildClient(status = HttpStatusCode.NotFound).get(testUrl, emptyMap()) }
        assertEquals(404, result.status)
    }

    @Test
    fun httpResponseWrapper_bodyMatchesResponseContent() {
        val result = with(HttpClientSync) { buildClient(body = "custom body").get(testUrl, emptyMap()) }
        assertEquals("custom body", result.body)
    }

    @Test
    fun httpResponseWrapper_headersContainAllResponseHeaders() {
        val result = with(HttpClientSync) { buildClient().get(testUrl, emptyMap()) }
        assertTrue(result.headers.containsKey("X-Response-Header"))
        assertEquals("response-value", result.headers["X-Response-Header"])
    }

    @Test
    fun httpResponseWrapper_multipleHeaderValuesAreJoinedWithComma() {
        val engine = MockEngine {
            respond(
                responseBody,
                HttpStatusCode.OK,
                headersOf("X-Multi" to listOf("val1", "val2")),
            )
        }
        val result = with(HttpClientSync) { HttpClient(engine).get(testUrl, emptyMap()) }
        assertEquals("val1,val2", result.headers["X-Multi"])
    }

    @Test
    fun httpResponseWrapper_emptyBodyDecodesAsEmptyString() {
        val result = with(HttpClientSync) { buildClient(body = "").get(testUrl, emptyMap()) }
        assertEquals("", result.body)
    }

    @Test
    fun allMethods_returnHttpResponseWrapper_withStatusAndBody() {
        val client = buildClient(body = responseBody, status = HttpStatusCode.OK)
        with(HttpClientSync) {
            assertEquals(200, client.get(testUrl, emptyMap()).status)
            assertEquals(200, client.post(testUrl, testBody, emptyMap()).status)
            assertEquals(200, client.put(testUrl, emptyMap()).status)
            assertEquals(200, client.patch(testUrl, emptyMap()).status)
            assertEquals(200, client.options(testUrl, emptyMap()).status)
            assertEquals(200, client.head(testUrl, emptyMap()).status)
            assertEquals(200, client.delete(testUrl, emptyMap()).status)
        }
    }

    @Test
    fun httpResponseWrapper_usesInnerHeaders_whenInnerHeadersKeyPresent() {
        val innerHeaders = mapOf("etag" to "\"abc123\"", "vsdm-pz" to "some-pz-value")
        val result = with(HttpClientSync) {
            buildClientWithInnerAttributes(innerHeaders = innerHeaders).get(testUrl, emptyMap())
        }

        assertEquals("\"abc123\"", result.headers["etag"])
        assertEquals("some-pz-value", result.headers["vsdm-pz"])
        assertTrue(!result.headers.containsKey("X-Response-Header"))
    }

    @Test
    fun httpResponseWrapper_fallsBackToRawHeaders_whenInnerHeadersKeyAbsent() {
        val result = with(HttpClientSync) {
            buildClientWithInnerAttributes(innerHeaders = null).get(testUrl, emptyMap())
        }
        assertTrue(result.headers.containsKey("X-Response-Header"))
        assertEquals("response-value", result.headers["X-Response-Header"])
    }

    @Test
    fun httpResponseWrapper_usesInnerStatus_whenInnerStatusKeyPresent() {
        val result = with(HttpClientSync) {
            buildClientWithInnerAttributes(status = HttpStatusCode.OK, innerStatus = 404)
                .get(testUrl, emptyMap())
        }
        assertEquals(404, result.status)
    }

    @Test
    fun httpResponseWrapper_fallsBackToRawStatus_whenInnerStatusKeyAbsent() {
        val result = with(HttpClientSync) {
            buildClientWithInnerAttributes(status = HttpStatusCode.OK, innerStatus = null)
                .get(testUrl, emptyMap())
        }
        assertEquals(200, result.status)
    }

    @Test
    fun httpResponseWrapper_usesInnerStatusAndHeadersTogether_matchingAslDecryptionScenario() {
        val innerHeaders = mapOf("etag" to "\"xyz\"", "vsdm-pz" to "pz-token")
        val result = with(HttpClientSync) {
            buildClientWithInnerAttributes(
                status = HttpStatusCode.OK,
                innerStatus = 200,
                innerHeaders = innerHeaders,
            ).get(testUrl, emptyMap())
        }
        assertEquals(200, result.status)
        assertEquals("\"xyz\"", result.headers["etag"])
        assertEquals("pz-token", result.headers["vsdm-pz"])
        assertTrue(!result.headers.containsKey("X-Response-Header"))
    }

    @Test
    fun httpResponseWrapper_bodyUnaffectedByInnerAttributes() {
        val result = with(HttpClientSync) {
            buildClientWithInnerAttributes(
                body = responseBody,
                innerStatus = 404,
                innerHeaders = mapOf("etag" to "\"abc\""),
            ).get(testUrl, emptyMap())
        }
        assertEquals(responseBody, result.body)
    }

    @Test
    fun toZetaResponse_usesInnerStatus_whenPresent() = runTest {
        val client = buildClientWithInnerAttributes(status = HttpStatusCode.OK, innerStatus = 404)
        val httpResponse = client.get(testUrl)

        val result = toZetaResponse(httpResponse)

        assertEquals(HttpStatusCode.NotFound, result.status)
    }

    @Test
    fun toZetaResponse_usesInnerHeaders_whenPresent() = runTest {
        val innerHeaders = mapOf("etag" to "\"abc123\"")
        val client = buildClientWithInnerAttributes(innerHeaders = innerHeaders)
        val httpResponse = client.get(testUrl)

        val result = toZetaResponse(httpResponse)

        assertEquals("\"abc123\"", result.headers["etag"])
        assertFalse(result.headers.containsKey("X-Response-Header"))
    }

    @Test
    fun toZetaResponse_fallsBackToRawStatusAndHeaders_whenInnerAttributesAbsent() = runTest {
        val client = buildClientWithInnerAttributes(status = HttpStatusCode.OK, innerStatus = null, innerHeaders = null)
        val httpResponse = client.get(testUrl)

        val result = toZetaResponse(httpResponse)

        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals("response-value", result.headers["X-Response-Header"])
    }

    private fun buildClientWithInnerAttributes(
        body: String = responseBody,
        status: HttpStatusCode = HttpStatusCode.OK,
        innerStatus: Int? = null,
        innerHeaders: Map<String, String>? = null,
    ): HttpClient {
        val engine = MockEngine { request ->
            captured += CapturedRequest(
                method = request.method.value,
                url = request.url.toString(),
                headers = request.headers,
                body = request.body.toByteArray().decodeToString(),
                contentType = request.body.contentType,
            )
            respond(body, status, responseHeaders)
        }
        return HttpClient(engine) {
            install(
                createClientPlugin("TestInnerAttributesPlugin") {
                    val phase = PipelinePhase("TestInnerAttributes")
                    client.responsePipeline.insertPhaseBefore(HttpResponsePipeline.Receive, phase)
                    client.responsePipeline.intercept(phase) {
                        if (innerStatus != null) context.attributes.put(InnerStatusKey, innerStatus)
                        if (innerHeaders != null) context.attributes.put(InnerHeadersKey, innerHeaders)
                    }
                },
            )
        }
    }
}
