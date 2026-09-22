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
package de.gematik.zeta.sdk.flow

import de.gematik.zeta.sdk.flow.RequestEvaluatorImplTest.FakeForwardingClient
import de.gematik.zeta.sdk.network.http.client.InnerHeadersKey
import de.gematik.zeta.sdk.network.http.client.InnerStatusKey
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.headersOf
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AslResponseEvaluatorTest {
    private val realAslErrorBody = byteArrayOf(
        0xa3.toByte(), 0x6b, 0x4d, 0x65, 0x73, 0x73, 0x61, 0x67, 0x65, 0x54, 0x79, 0x70, 0x65,
        0x65, 0x45, 0x72, 0x72, 0x6f, 0x72,
        0x69, 0x45, 0x72, 0x72, 0x6f, 0x72, 0x43, 0x6f, 0x64, 0x65,
        0x04,
        0x6c, 0x45, 0x72, 0x72, 0x6f, 0x72, 0x4d, 0x65, 0x73, 0x73, 0x61, 0x67, 0x65,
        0x70, 0x50, 0x55, 0x2f, 0x6e, 0x6f, 0x6e, 0x50, 0x55, 0x20, 0x66, 0x61, 0x69, 0x6c, 0x75, 0x72, 0x65,
    )

    private fun dummyCtx(
        storage: InMemoryStorage = InMemoryStorage(),
    ) = FlowContextImpl(
        ResourceScope("", emptyList()),
        FakeForwardingClient(),
        storage,
        clock = SystemZetaClock,
    )

    @Test
    fun evaluate_returns_proceed_on_200_without_inner_status() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(HttpStatusCode.OK)

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_proceed_on_200_with_inner_404_when_origin_is_not_pep() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(
            status = HttpStatusCode.OK,
            innerHeaders = mapOf(
                "zeta-error-origin" to
                    "resource-server",
            ),
            innerStatus = 404,
        )

        val retryState = FlowOrchestrator.RetryState()

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(),
            retryState,
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
        assertFalse(retryState.hasAttemptedDiscoveryRefresh)
    }

    @Test
    fun evaluate_returns_proceed_on_200_with_inner_404_when_discovery_refresh_already_attempted() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(
            status = HttpStatusCode.OK,
            innerHeaders = mapOf("zeta-error-origin" to "pep"),
            innerStatus = 404,
        )

        val retryState = FlowOrchestrator.RetryState().apply {
            hasAttemptedDiscoveryRefresh = true
        }

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(),
            retryState,
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
        assertTrue(retryState.hasAttemptedDiscoveryRefresh)
    }

    @Test
    fun evaluate_returns_proceed_on_200_with_non_404_inner_status() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 400,
        )

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_perform_asl_on_500() = runTest {
        // Arrange
        val storage = InMemoryStorage()
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(HttpStatusCode.InternalServerError)

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(storage),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(FlowNeed.Asl, directive.need)
    }

    @Test
    fun evaluate_returns_abort_with_real_error_code_on_valid_cbor_body() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(
            status = HttpStatusCode.Forbidden,
            body = realAslErrorBody,
        )

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Abort>(directive)

        val error = directive.error
        assertIs<ZetaClientError.AslError>(error)
        assertEquals(4, error.errorCode)
        assertEquals("PU/nonPU failure", error.detail)
    }

    @Test
    fun evaluate_returns_abort_with_sentinel_error_on_malformed_body() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(
            status = HttpStatusCode.Forbidden,
            body = byteArrayOf(0x01, 0x02, 0x03),
        )

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Abort>(directive)

        val error = directive.error
        assertIs<ZetaClientError.AslError>(error)
        assertEquals(-1, error.errorCode)
        assertEquals(
            "Unparseable ASL error response (HTTP 403)",
            error.detail,
        )
    }

    @Test
    fun evaluate_returns_abort_with_sentinel_error_on_empty_body() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val resp = aslResponseWith(
            status = HttpStatusCode.Forbidden,
            body = ByteArray(0),
        )

        // Act
        val directive = evaluator.evaluate(
            resp.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Abort>(directive)

        val error = directive.error
        assertIs<ZetaClientError.AslError>(error)
        assertEquals(-1, error.errorCode)
    }

    @Test
    fun evaluate_returnsProceed_whenOuter200AndInnerStatusMissing() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val response = aslResponseWith(
            status = HttpStatusCode.OK,
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returnsProceed_whenOuter200AndInnerStatusIsNotHandled() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 400,
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_performsAuthentication_whenInner401ComesFromPep() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val retryState = FlowOrchestrator.RetryState()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 401,
            innerHeaders = mapOf(
                "zeta-error-origin" to "pep",
            ),
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            retryState,
        )

        // Assert
        val perform = assertIs<FlowDirective.Perform>(directive)

        assertEquals(
            FlowNeed.Authentication,
            perform.need,
        )
        assertEquals(1, retryState.stepUpAttempts)
    }

    @Test
    fun evaluate_performsAuthentication_whenInner403ComesFromPep() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val retryState = FlowOrchestrator.RetryState()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 403,
            innerHeaders = mapOf(
                "zeta-error-origin" to "pep",
            ),
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            retryState,
        )

        // Assert
        val perform = assertIs<FlowDirective.Perform>(directive)

        assertEquals(
            FlowNeed.Authentication,
            perform.need,
        )
        assertEquals(1, retryState.stepUpAttempts)
    }

    @Test
    fun evaluate_returnsProceed_whenInner401DoesNotComeFromPep() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 401,
            innerHeaders = mapOf(
                "zeta-error-origin" to "resource-server",
            ),
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_performsConfigurationFiles_whenInner404ComesFromPep() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val retryState = FlowOrchestrator.RetryState()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 404,
            innerHeaders = mapOf(
                "zeta-error-origin" to "pep",
            ),
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            retryState,
        )

        // Assert
        val perform = assertIs<FlowDirective.Perform>(directive)

        assertEquals(
            FlowNeed.ConfigurationFiles,
            perform.need,
        )
        assertTrue(
            retryState.hasAttemptedDiscoveryRefresh,
        )
    }

    @Test
    fun evaluate_returnsProceed_whenInner404DoesNotComeFromPep() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 404,
            innerHeaders = mapOf(
                "zeta-error-origin" to "resource-server",
            ),
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_findsErrorOriginHeader_caseInsensitive() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 404,
            innerHeaders = mapOf(
                "ZETA-ERROR-ORIGIN" to "PEP",
            ),
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Perform>(directive)
    }

    private suspend fun aslResponseWith(
        status: HttpStatusCode,
        body: ByteArray = ByteArray(0),
        contentType: String = "application/cbor",
        innerStatus: Int? = null,
        innerHeaders: Map<String, String>? = null,
    ): HttpResponse {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    contentType,
                ),
            )
        }

        val client = HttpClient(engine)

        val response = client.request(
            HttpRequestBuilder().apply {
                url.takeFrom(
                    URLBuilder("https://test"),
                )
            },
        )

        innerStatus?.let {
            response.call.attributes.put(
                InnerStatusKey,
                it,
            )
        }

        innerHeaders?.let {
            response.call.attributes.put(
                InnerHeadersKey,
                it,
            )
        }

        return response
    }

    @Test
    fun evaluate_returnsProceed_on200_whenInnerStatusMissing() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val response = aslResponseWith(
            status = HttpStatusCode.OK,
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returnsProceed_on200_whenInnerStatusIsNot404() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()
        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 400,
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returnsProceed_on200_whenInnerStatus404AndDiscoveryAlreadyAttempted() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 404,
            innerHeaders = mapOf(
                "zeta-error-origin" to "pep",
            ),
        )

        val retryState = FlowOrchestrator.RetryState().apply {
            hasAttemptedDiscoveryRefresh = true
        }

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            retryState,
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returnsProceed_on200_whenInnerStatus404NotFromPep() = runTest {
        // Arrange
        val evaluator = AslResponseEvaluator()

        val response = aslResponseWith(
            status = HttpStatusCode.OK,
            innerStatus = 404,
            innerHeaders = mapOf(
                "zeta-error-origin" to "resource-server",
            ),
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            dummyCtx(),
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_clearsAslStorage_andPerformsAsl_on500() = runTest {
        // Arrange
        val storage = InMemoryStorage()
        val ctx = dummyCtx(storage)

        val evaluator = AslResponseEvaluator()
        val response = aslResponseWith(
            status = HttpStatusCode.InternalServerError,
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            ctx,
            FlowOrchestrator.RetryState(),
        )

        // Assert
        val perform = assertIs<FlowDirective.Perform>(directive)

        assertEquals(
            FlowNeed.Asl,
            perform.need,
        )
    }
}
