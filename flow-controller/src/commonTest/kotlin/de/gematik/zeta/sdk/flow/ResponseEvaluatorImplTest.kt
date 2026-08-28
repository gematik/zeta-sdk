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
import io.ktor.http.takeFrom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [ResponseEvaluatorImpl].
 */
class ResponseEvaluatorImplTest {

    private val evaluator = ResponseEvaluator { call, _, _ ->
        FlowDirective.Proceed(call.response)
    }

    @Test
    fun evaluate_returnsProceed_whenSucceeds() = runTest {
        // Arrange
        val dummyCtx = getDummyContextWithResource()
        val evaluator = ResponseEvaluatorImpl()
        val response = responseWith(HttpStatusCode.OK)

        // Act
        val directive = evaluator.evaluate(response.call, dummyCtx, FlowOrchestrator.RetryState())

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    /**
     * Response with 4xx proceed
     */
    @Test
    fun evaluate_returnsProceed_on404WithoutPepOrigin() = runTest {
        // Arrange
        val storage = InMemoryStorage()
        val evaluator = ResponseEvaluatorImpl()
        val response = responseWith(HttpStatusCode.NotFound)

        val ctx = FlowContextImpl(
            ResourceScope("", emptyList()),
            FakeForwardingClient(),
            storage,
        )

        // Act
        val directive = evaluator.evaluate(
            response.call,
            ctx,
            FlowOrchestrator.RetryState(),
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun handle_returnsProceed_whenStatusIsNotHandled() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.BadRequest,
        )

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = FlowOrchestrator.RetryState(),
            statusCode = HttpStatusCode.BadRequest,
            errorOrigin = null,
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun handle_returnsProceed_when404HasNoErrorOrigin() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.NotFound,
        )

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = FlowOrchestrator.RetryState(),
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = null,
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun handle_returnsProceed_when404ComesFromDifferentOrigin() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.NotFound,
        )

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = FlowOrchestrator.RetryState(),
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = "resource-server",
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun handle_performsConfigurationFiles_when404ComesFromPep() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.NotFound,
        )

        val retryState = FlowOrchestrator.RetryState()

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = retryState,
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = "pep",
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
    fun handle_acceptsPepOriginCaseInsensitive() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.NotFound,
        )

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = FlowOrchestrator.RetryState(),
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = "PEP",
        )

        // Assert
        assertIs<FlowDirective.Perform>(directive)
    }

    @Test
    fun handle_returnsProceed_whenDiscoveryRefreshWasAlreadyAttempted() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.NotFound,
        )

        val retryState = FlowOrchestrator.RetryState().apply {
            hasAttemptedDiscoveryRefresh = true
        }

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = retryState,
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = "pep",
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)

        assertTrue(
            retryState.hasAttemptedDiscoveryRefresh,
        )
    }

    @Test
    fun handle_doesNotPerformDiscoveryTwice_forSameRetryState() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.NotFound,
        )

        val ctx = dummyCtx()
        val retryState = FlowOrchestrator.RetryState()

        // Act
        val first = evaluator.handle(
            call = response.call,
            ctx = ctx,
            retryState = retryState,
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = "pep",
        )

        val second = evaluator.handle(
            call = response.call,
            ctx = ctx,
            retryState = retryState,
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = "pep",
        )

        // Assert
        assertIs<FlowDirective.Perform>(first)
        assertIs<FlowDirective.Proceed>(second)

        assertTrue(
            retryState.hasAttemptedDiscoveryRefresh,
        )
    }

    @Test
    fun handle_usesEffectiveStatus_whenOuterResponseIs200() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.OK,
        )

        val retryState = FlowOrchestrator.RetryState()

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = retryState,
            statusCode = HttpStatusCode.NotFound,
            errorOrigin = "pep",
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
    fun handleUnauthorized_returnsProceed_whenErrorDoesNotComeFromPep() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.Unauthorized,
        )

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = FlowOrchestrator.RetryState(),
            statusCode = HttpStatusCode.Unauthorized,
            errorOrigin = "resource-server",
        )

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    @Test
    fun handleUnauthorized_performsAuthentication_onFirstPep401() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.Unauthorized,
        )

        val retryState = FlowOrchestrator.RetryState()

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = retryState,
            statusCode = HttpStatusCode.Unauthorized,
            errorOrigin = "pep",
        )

        // Assert
        val perform = assertIs<FlowDirective.Perform>(directive)

        assertEquals(
            FlowNeed.Authentication,
            perform.need,
        )

        assertEquals(
            1,
            retryState.stepUpAttempts,
        )
    }

    @Test
    fun handleUnauthorized_performsFullAuthentication_onSecondPep401() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.Unauthorized,
        )

        val retryState = FlowOrchestrator.RetryState().apply {
            stepUpAttempts = 1
        }

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = retryState,
            statusCode = HttpStatusCode.Unauthorized,
            errorOrigin = "pep",
        )

        // Assert
        val perform = assertIs<FlowDirective.Perform>(directive)

        assertEquals(
            FlowNeed.Authentication,
            perform.need,
        )

        assertEquals(
            2,
            retryState.stepUpAttempts,
        )
    }

    @Test
    fun handleUnauthorized_aborts_whenStepUpAlreadyAttemptedTwice() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.Forbidden,
        )

        val retryState = FlowOrchestrator.RetryState().apply {
            stepUpAttempts = 2
        }

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = retryState,
            statusCode = HttpStatusCode.Forbidden,
            errorOrigin = "pep",
        )

        // Assert
        assertIs<FlowDirective.Abort>(directive)
    }

    @Test
    fun handleUnauthorized_usesInnerStatus_whenOuterResponseIs200() = runTest {
        // Arrange
        val response = responseWith(
            HttpStatusCode.OK,
        )

        val retryState = FlowOrchestrator.RetryState()

        // Act
        val directive = evaluator.handle(
            call = response.call,
            ctx = dummyCtx(),
            retryState = retryState,
            statusCode = HttpStatusCode.Unauthorized,
            errorOrigin = "pep",
        )

        // Assert
        val perform = assertIs<FlowDirective.Perform>(directive)

        assertEquals(
            FlowNeed.Authentication,
            perform.need,
        )

        assertEquals(
            1,
            retryState.stepUpAttempts,
        )
    }

    private fun dummyCtx(
        storage: InMemoryStorage = InMemoryStorage(),
    ): FlowContext =
        FlowContextImpl(
            ResourceScope("", emptyList()),
            FakeForwardingClient(),
            storage,
        )

    private suspend fun responseWith(
        status: HttpStatusCode,
        headers: Headers = Headers.Empty,
    ): HttpResponse {
        val engine = MockEngine {
            respond(
                content = "",
                status = status,
                headers = headers,
            )
        }

        val client = HttpClient(engine)

        return client.request(
            HttpRequestBuilder().apply {
                url.takeFrom(
                    URLBuilder("https://test"),
                )
            },
        )
    }
}
