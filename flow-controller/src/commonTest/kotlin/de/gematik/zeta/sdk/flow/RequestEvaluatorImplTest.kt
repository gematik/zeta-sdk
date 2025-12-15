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

import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.InMemoryStorage
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [RequestEvaluatorImpl].
 */
class RequestEvaluatorImplTest {

    /**public paths yield no pre-send needs.**/
    @Test
    fun evaluate_return_no_needs() = runTest {
        // Arrange
        val req = HttpRequestBuilder().apply {
            url.takeFrom(URLBuilder("https://health"))
        }

        val evaluator = RequestEvaluatorImpl()

        // Act
        val needs = evaluator.evaluate(req, FlowContextImpl("", FakeForwardingClient(), InMemoryStorage()))

        // Assert
        assertEquals(emptyList(), needs, "Public paths must not produce pre-send needs")
    }

    class FakeForwardingClient : ForwardingClient {
        override suspend fun executeOnce(builder: HttpRequestBuilder): ZetaHttpResponse {
            TODO("Not yet implemented")
        }
    }
}
