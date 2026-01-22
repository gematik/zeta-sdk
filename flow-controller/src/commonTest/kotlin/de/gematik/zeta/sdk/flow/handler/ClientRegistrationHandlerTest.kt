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

package de.gematik.zeta.sdk.flow.handler

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.ApiVersionStatus
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowContextImpl
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.RequestEvaluatorImplTest.FakeForwardingClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ClientRegistrationHandlerTest {
    private val MAX_RETRIES = 3

    @Test
    fun handle_success_onFirstAttempt() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")

        val mock = MockEngine {
            respond(
                content = Json.encodeToString(response),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val handler = createHandler(mock)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Done)
    }

    @Test
    fun handle_retriesOnBadRequest() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        var callCount = 0

        val mock = MockEngine {
            callCount++
            if (callCount == 1) {
                respond(
                    content = Json.encodeToString(response),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    content = Json.encodeToString(response),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }

        val handler = createHandler(mock)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Done)
        assertEquals(MAX_RETRIES - 1, mock.requestHistory.size)
    }

    @Test
    fun handle_retriesUnauthorized() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        var callCount = 0

        val mock = MockEngine {
            callCount++
            when (callCount) {
                1, 2 ->
                    respond(
                        content = Json.encodeToString(response),
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )

                else ->
                    respond(
                        content = Json.encodeToString(response),
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
            }
        }

        val handler = createHandler(mock)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Done)
        assertEquals(MAX_RETRIES, mock.requestHistory.size)
    }

    @Test
    fun handle_returnsBadRequest_whenRetriesIsReached() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = MockEngine {
            respond(
                content = Json.encodeToString(response),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val handler = createHandler(mock)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Error)
        assertEquals(MAX_RETRIES, mock.requestHistory.size)
    }

    @Test
    fun handle_failsWithoutRetry_onStatus403() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = MockEngine {
            respond(
                content = Json.encodeToString(response),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val handler = createHandler(mock)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Error)
        assertEquals(HttpStatusCode.Forbidden, result.httpResponse.status)
        assertEquals(1, mock.requestHistory.size)
    }

    @Test
    fun handle_failsWithoutRetry_onStatus409() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = MockEngine {
            respond(
                content = Json.encodeToString(response),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val handler = createHandler(mock)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Error)
        assertEquals(HttpStatusCode.Conflict, result.httpResponse.status)
        assertEquals(1, mock.requestHistory.size)
    }

    @Test
    fun handle_retries5Times_withMaxRetry5() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = MockEngine {
            respond(
                content = Json.encodeToString(response),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val handler = createHandler(mock, 5)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Error)
        assertEquals(HttpStatusCode.InternalServerError, result.httpResponse.status)
        assertEquals(5, mock.requestHistory.size)
    }

    private fun createClient(mockEngine: MockEngine): ZetaHttpClient =
        ZetaHttpClientBuilder()
            .build(mockEngine)

    private fun createHandler(mock: MockEngine, maxRetries: Int = MAX_RETRIES): ClientRegistrationHandler {
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
