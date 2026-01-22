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

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.ApiVersionStatus
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.flow.RequestEvaluatorImplTest.FakeForwardingClient
import de.gematik.zeta.sdk.flow.handler.ClientRegistrationHandler
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for [FlowOrchestrator].
 */
class FlowOrchestratorTest {

    private val requestEvaluatorWithNoNeeds = RequestEvaluator { _, _ -> emptyList() }

    /** Proceed path: returns the mocked 200 response immediately. */
    @Test
    fun run_proceed_on_200() = runTest {
        // Arrange
        val forwarding = getMockClient(HttpStatusCode.OK)
        val dummyCtx = getDummyContextWithResource(forwarding)
        val orchestrator = FlowOrchestrator(
            listOf(),
            requestEvaluator = requestEvaluatorWithNoNeeds,
        )

        // Act
        val resp = orchestrator.run(
            HttpRequestBuilder().apply { url("https://test") },
            dummyCtx,
        )

        // Assert
        assertEquals(200, resp.response.status.value)
    }

    @Test
    fun run_returnsFlowAbortException_whenClientDataIsInvalid() = runTest {
        // Arrange
        val forwarding = getMockClient(HttpStatusCode.OK)
        val dummyCtx = getDummyContextWithResource(forwarding)

        val mock = MockEngine {
            respond(
                content = Json.encodeToString("response"),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val orchestrator = FlowOrchestrator(
            listOf(createHandler(mock)),
            requestEvaluator = { _, _ -> listOf(FlowNeed.ClientRegistration) },
        )

        // Act
        // Assert
        val result = orchestrator.run(
            HttpRequestBuilder().apply { url("https://test") },
            dummyCtx,
        )

        assertEquals(HttpStatusCode.BadRequest, result.response.status)
    }

    /** Abort path: non-2xx (500) throws an exception. */
    @Test
    fun run_abort_on_500() = runTest {
        // Arrange
        val forwarding = getMockClient(HttpStatusCode.InternalServerError)
        val orchestrator = FlowOrchestrator(
            listOf(),
        )

        // Assert
        assertFailsWith<Exception> {
            // Act
            orchestrator.run(
                HttpRequestBuilder().apply { url("https://test") },
                FlowContextImpl("", forwarding, InMemoryStorage()),
            )
        }
    }

    /** Pre-send needs from RequestEvaluator are executed once before first call. */
    @Test
    fun run_executes_request_needs() = runTest {
        // Arrange
        val forwarding = getMockClient(HttpStatusCode.OK)
        val dummyCtx = getDummyContextWithResource(forwarding)
        val handler = RecordingDoneHandler(FlowNeed.ConfigurationFiles)
        val orchestrator = FlowOrchestrator(
            requestEvaluator = { _, _ -> listOf(FlowNeed.ConfigurationFiles) },
            handlers = listOf(handler),
        )

        // Act
        val resp = orchestrator.run(
            HttpRequestBuilder(),
            dummyCtx,
        )

        // Assert
        assertTrue(handler.called, "Handler must be executed")
        assertEquals(HttpStatusCode.OK.value, resp.response.status.value)
    }

    /** Response-time needs are executed, after handler Done the evaluator proceeds. */
    @Test
    fun run_executes_response_needs() = runTest {
        // Arrange
        val forwarding = getMockClient(HttpStatusCode.OK)
        val dummyCtx = getDummyContextWithResource(forwarding)
        val handler = RecordingDoneHandler(FlowNeed.ConfigurationFiles)

        val responseEvaluator = ResponseEvaluator { resp, _ ->
            if (!handler.called) {
                FlowDirective.Perform(FlowNeed.ConfigurationFiles)
            } else {
                FlowDirective.Proceed(resp.response)
            }
        }
        val orchestrator = FlowOrchestrator(
            requestEvaluator = requestEvaluatorWithNoNeeds,
            responseEvaluator = responseEvaluator,
            handlers = listOf(handler),
        )

        // Act
        val resp = orchestrator.run(
            HttpRequestBuilder(),
            dummyCtx,
        )

        // Assert
        assertTrue(handler.called, "Handler must be executed")
        assertEquals(HttpStatusCode.OK.value, resp.response.status.value)
    }

    private fun getMockClient(status: HttpStatusCode): ForwardingClient {
        val engine = MockEngine { respond("", status) }
        val ktor = ZetaHttpClient(HttpClient(engine))

        return object : ForwardingClient {
            override suspend fun executeOnce(builder: HttpRequestBuilder): ZetaHttpResponse =
                ktor.request {
                    takeFrom(builder)
                }
        }
    }

    private fun createClient(mockEngine: MockEngine): ZetaHttpClient =
        ZetaHttpClientBuilder()
            .build(mockEngine)

    private fun createHandler(mock: MockEngine, maxRetries: Int = 3): ClientRegistrationHandler {
        val client = createClient(mock)
        val api = ClientRegistrationApiImpl(client)
        val tpm = FakeTpmProvider(false)

        return ClientRegistrationHandler("TestClientName", api, tpm, maxRetries)
    }

    private fun createContext(): FlowContext = FlowContextImpl("test", FakeForwardingClient(), InMemoryStorage(), configurationStorage = FakeConfigurationStorage())

    private class FakeTpmProvider(override val isHardwareBacked: Boolean) : TpmProvider {
        override suspend fun generateClientInstanceKey(): PublicKeyOut {
            return PublicKeyOut(byteArrayOf(1), Jwk("", "", "", "", "", "", ""))
        }

        override suspend fun generateDpopKey(): PublicKeyOut {
            TODO("Not yet implemented")
        }

        override suspend fun signWithClientKey(input: ByteArray): ByteArray {
            TODO("Not yet implemented")
        }

        override suspend fun signWithDpopKey(input: ByteArray): ByteArray {
            TODO("Not yet implemented")
        }

        override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray {
            TODO("Not yet implemented")
        }

        override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray {
            TODO("Not yet implemented")
        }

        override suspend fun randomUuid(): Uuid {
            TODO("Not yet implemented")
        }

        override suspend fun getRegistrationNumber(certificate: ByteArray): String {
            TODO("Not yet implemented")
        }

        override fun forget() {
            TODO("Not yet implemented")
        }
    }

    private class FakeConfigurationStorage : ConfigurationStorage {
        override suspend fun getProtectedResource(resourceUrl: String): ProtectedResourceMetadata? {
            TODO("Not yet implemented")
        }

        override suspend fun saveProtectedResource(protectedRes: String): ProtectedResourceMetadata {
            TODO("Not yet implemented")
        }

        override suspend fun getAuthServers(): List<AuthorizationServerMetadata> {
            TODO("Not yet implemented")
        }

        override suspend fun getAuthServer(resource: String): AuthorizationServerMetadata? {
            return AuthorizationServerMetadata(
                issuer = "issuer",
                authorizationEndpoint = "",
                tokenEndpoint = "token_endpoint",
                nonceEndpoint = "",
                openidProvidersEndpoint = "test open id",
                jwksUri = "",
                scopesSupported = listOf(""),
                responseTypesSupported = listOf("TOKEN"),
                responseModesSupported = listOf(""),
                grantTypesSupported = listOf(""),
                tokenEndpointAuthMethodsSupported = listOf(""),
                tokenEndpointAuthSigningAlgValuesSupported = listOf(""),
                serviceDocumentation = "",
                uiLocalesSupported = listOf(""),
                codeChallengeMethodsSupported = listOf(""),
                apiVersionsSupported =
                listOf(
                    ApiVersion(
                        majorVersion = 1,
                        version = "",
                        status = ApiVersionStatus.STABLE,
                        documentationUri = "",
                    ),
                ),
            )
        }

        override suspend fun linkResourceToAuthorizationServer(resource: String, authServerMetadata: AuthorizationServerMetadata) {
            TODO("Not yet implemented")
        }

        override suspend fun aslRequired(resource: String): Boolean {
            TODO("Not yet implemented")
        }

        override suspend fun clear() {
            TODO("Not yet implemented")
        }
    }
}
