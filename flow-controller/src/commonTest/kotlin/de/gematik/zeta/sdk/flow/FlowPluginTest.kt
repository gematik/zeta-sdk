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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [FlowPlugin].
 */
class FlowPluginSuccessTest {

    /**
     * Unhandled status causes the flow to abort and throws an exception.
     */
    @Test
    fun plugin_abort_unhandled_status() = runTest {
        val fo = FlowOrchestrator(listOf())
        val ctx = FlowContext(
            object : ForwardingClient {
                override suspend fun executeOnce(builder: HttpRequestBuilder): HttpResponse {
                    TODO("Not yet implemented")
                }
            },
            storage = InMemoryStorage(),
        )

        // Arrange
        val engine = MockEngine { respond("", HttpStatusCode.ExpectationFailed) }
        val client = HttpClient(engine) {
            install(zetaPlugin(fo, ctx))
        }

        // Assert
        assertFailsWith<Exception> {
            // Act
            client.get("https://test")
        }
    }
}
