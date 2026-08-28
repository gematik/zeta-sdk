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

import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.headersOf
import io.ktor.http.takeFrom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StatusCodeEvaluatorTest {

    private val evaluator = StatusCodeEvaluator()

    @Test
    fun evaluate_returns_proceed_on_200() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.OK)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_proceed_on_201() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.Created)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_proceed_on_401_not_from_pep() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.Unauthorized)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_perform_authentication_on_401_from_pep() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.Unauthorized, pepHeaders())

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(FlowNeed.Authentication, directive.need)
    }

    @Test
    fun evaluate_keeps_refresh_token_on_first_401_from_pep() = runTest {
        // Arrange
        val ctx = dummyCtx()
        ctx.authenticationStorage.saveAccessTokens("access", "refresh", 9999L)
        val resp = responseWith(HttpStatusCode.Unauthorized, pepHeaders())
        val retryState = FlowOrchestrator.RetryState()

        // Act
        val directive = evaluator.evaluate(resp.call, ctx, retryState)

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(FlowNeed.Authentication, directive.need)
        assertNull(ctx.authenticationStorage.getAccessToken())
        assertEquals("refresh", ctx.authenticationStorage.getRefreshToken())
        assertEquals(1, retryState.stepUpAttempts)
    }

    @Test
    fun evaluate_clears_refresh_token_on_second_401_from_pep() = runTest {
        // Arrange
        val ctx = dummyCtx()
        ctx.authenticationStorage.saveAccessTokens("access", "refresh", 9999L)
        val resp = responseWith(HttpStatusCode.Unauthorized, pepHeaders())
        val retryState = FlowOrchestrator.RetryState().apply { stepUpAttempts = 1 }

        // Act
        val directive = evaluator.evaluate(resp.call, ctx, retryState)

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(FlowNeed.Authentication, directive.need)
        assertNull(ctx.authenticationStorage.getRefreshToken())
        assertEquals(2, retryState.stepUpAttempts)
    }

    @Test
    fun evaluate_aborts_on_third_401_from_pep() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.Unauthorized, pepHeaders())
        val retryState = FlowOrchestrator.RetryState().apply { stepUpAttempts = 2 }

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), retryState)

        // Assert
        assertIs<FlowDirective.Abort>(directive)
        assertIs<ZetaClientError.StepUpFailed>(directive.error)
    }

    @Test
    fun evaluate_returns_abort_on_400() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.BadRequest)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_proceed_on_403_not_from_pep() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.Forbidden)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_keeps_refresh_token_on_first_403_from_pep() = runTest {
        // Arrange
        val ctx = dummyCtx()
        ctx.authenticationStorage.saveAccessTokens("access", "refresh", 9999L)
        val resp = responseWith(HttpStatusCode.Forbidden, pepHeaders())

        // Act
        val directive = evaluator.evaluate(resp.call, ctx, FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(FlowNeed.Authentication, directive.need)
        assertEquals("refresh", ctx.authenticationStorage.getRefreshToken())
    }

    @Test
    fun evaluate_returns_abort_on_404() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.NotFound)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_abort_on_405() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.MethodNotAllowed)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_abort_on_409() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.Conflict)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun evaluate_returns_preform_on_429() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.TooManyRequests)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
    }

    @Test
    fun evaluate_returns_perform_on_500() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.InternalServerError)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
    }

    @Test
    fun evaluate_returns_perform_on_502() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.BadGateway)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
    }

    @Test
    fun evaluate_returns_perform_on_504() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.GatewayTimeout)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
    }

    @Test
    fun evaluate_returns_abort_on_unhandled_status() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode(418, "test"))

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Abort>(directive)
    }

    @Test
    fun evaluate_returns_perform_with_retry_after_header_on_429() = runTest {
        // Arrange
        val engine = MockEngine { respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "10")) }
        val client = HttpClient(engine)
        val resp = client.request(HttpRequestBuilder().apply { url.takeFrom(URLBuilder("https://test")) })

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(10_000L, (directive.need as FlowNeed.Retry).delayMs)
    }

    @Test
    fun evaluate_returns_perform_with_default_delay_when_no_retry_after_on_429() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.TooManyRequests)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(StatusCodeEvaluator.DEFAULT_RETRY_DELAY_MS, (directive.need as FlowNeed.Retry).delayMs)
    }

    @Test
    fun evaluate_returns_perform_with_default_delay_on_500() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.InternalServerError)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(StatusCodeEvaluator.DEFAULT_RETRY_DELAY_MS, (directive.need as FlowNeed.Retry).delayMs)
    }

    @Test
    fun evaluate_returns_perform_with_default_delay_on_502() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.BadGateway)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(StatusCodeEvaluator.DEFAULT_RETRY_DELAY_MS, (directive.need as FlowNeed.Retry).delayMs)
    }

    @Test
    fun evaluate_returns_perform_with_default_delay_on_504() = runTest {
        // Arrange
        val resp = responseWith(HttpStatusCode.GatewayTimeout)

        // Act
        val directive = evaluator.evaluate(resp.call, dummyCtx(), FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Perform>(directive)
        assertEquals(StatusCodeEvaluator.DEFAULT_RETRY_DELAY_MS, (directive.need as FlowNeed.Retry).delayMs)
    }

    private fun dummyCtx(): FlowContext {
        val storage = InMemoryStorage()
        return FlowContextImpl(ResourceScope("", emptyList()), RequestEvaluatorImplTest.FakeForwardingClient(), storage)
    }

    private fun pepHeaders() = headersOf(ResponseEvaluator.ZETA_ERROR_ORIGIN, ResponseEvaluator.PEP)

    private suspend fun responseWith(status: HttpStatusCode, headers: Headers = Headers.Empty): HttpResponse {
        val engine = MockEngine { respond("", status, headers) }
        val client = HttpClient(engine)
        return client.request(
            HttpRequestBuilder().apply {
                url.takeFrom(URLBuilder("https://test"))
            },
        )
    }
}
