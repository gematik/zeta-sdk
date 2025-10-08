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

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Unit tests for [ResponseEvaluatorImplTest].
 */
class ResponseEvaluatorImplTest {
    /**
     * Response with 2xx proceeds
     */
    @Test
    fun evaluate_returns_proceed_when_succeeds() = runTest {
        // Arrange
        val evaluator = ResponseEvaluatorImpl()
        val req = HttpRequestBuilder()
        val resp = responseWith(HttpStatusCode.OK)

        // Act
        val directive = evaluator.evaluate(req, resp.call)

        // Assert
        assertIs<FlowDirective.Proceed>(directive)
    }

    /**
     * Response with 4xx aborts
     */
    @Test
    fun evaluate_returns_abort_on_status_code_404() = runTest {
        // Arrange
        val evaluator = ResponseEvaluatorImpl()
        val req = HttpRequestBuilder()
        val resp = responseWith(HttpStatusCode.NotFound)

        // Act
        val directive = evaluator.evaluate(req, resp.call)

        // Assert
        assertIs<FlowDirective.Abort>(directive)
    }

    private suspend fun responseWith(status: HttpStatusCode): HttpResponse {
        val engine = MockEngine { respond("", status) }
        val client = HttpClient(engine)

        return client.request(
            HttpRequestBuilder().apply {
                url.takeFrom(URLBuilder("https://test"))
            },
        )
    }
}
