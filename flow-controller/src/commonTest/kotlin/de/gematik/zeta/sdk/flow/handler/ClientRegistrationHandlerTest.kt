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
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.configuration.models.ZetaAslUse
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FakeApi
import de.gematik.zeta.sdk.flow.FakeValidator
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowContextImpl
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.RequestEvaluatorImplTest.FakeForwardingClient
import de.gematik.zeta.sdk.flow.getDummyAuthServerObject
import de.gematik.zeta.sdk.flow.getDummyFlowContext
import de.gematik.zeta.sdk.flow.getDummyProtectedResourceObject
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ClientRegistrationHandlerTest {
    private val MAX_RETRIES = 3
    private val CURRENT_KID = "current-client-key"
    private val REGISTRATION_ENDPOINT = "https://as.example.com/register"

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
        assertEquals(HttpStatusCode.Forbidden, result.httpResponse!!.status)
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
        assertEquals(HttpStatusCode.Conflict, result.httpResponse!!.status)
        assertEquals(1, mock.requestHistory.size)
    }

    @Test
    fun handle_retries3Times_withMaxRetry3() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = MockEngine {
            respond(
                content = Json.encodeToString(response),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val handler = createHandler(mock)
        val ctx = createContext()

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Error)
        assertEquals(HttpStatusCode.InternalServerError, result.httpResponse!!.status)
        assertEquals(3, mock.requestHistory.size)
    }

    @Test
    fun handle_reRegisters_whenCachedRegistrationUsedDifferentClientKey() = runTest {
        // Arrange
        val mock = respondWithCreated(ClientRegistrationResponse(clientId = "fresh-client"))
        val handler = createHandler(mock)
        val ctx = createContext(REGISTRATION_ENDPOINT)
        ctx.clientRegistrationStorage.saveRegistration(
            REGISTRATION_ENDPOINT,
            ClientRegistrationResponse(clientId = "stale-client", jwks = Jwks(listOf(createClientKey("gone-key")))),
        )

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Done)
        assertEquals(1, mock.requestHistory.size)
        assertEquals("fresh-client", ctx.clientRegistrationStorage.getClientId(REGISTRATION_ENDPOINT))
    }

    @Test
    fun handle_skipsRegistration_whenCachedRegistrationUsedCurrentClientKey() = runTest {
        // Arrange
        val mock = respondWithCreated(ClientRegistrationResponse(clientId = "fresh-client"))
        val handler = createHandler(mock)
        val ctx = createContext(REGISTRATION_ENDPOINT)
        ctx.clientRegistrationStorage.saveRegistration(
            REGISTRATION_ENDPOINT,
            ClientRegistrationResponse(clientId = "cached-client", jwks = Jwks(listOf(createClientKey(CURRENT_KID)))),
        )

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Done)
        assertEquals(0, mock.requestHistory.size)
        assertEquals("cached-client", ctx.clientRegistrationStorage.getClientId(REGISTRATION_ENDPOINT))
    }

    @Test
    fun handle_reRegisters_whenCachedRegistrationHasNoRecordedClientKey() = runTest {
        // Arrange
        val mock = respondWithCreated(ClientRegistrationResponse(clientId = "fresh-client"))
        val handler = createHandler(mock)
        val ctx = createContext(REGISTRATION_ENDPOINT)
        ctx.clientRegistrationStorage.saveRegistration(
            REGISTRATION_ENDPOINT,
            ClientRegistrationResponse(clientId = "legacy-client"),
        )

        // Act
        val result = handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        assertTrue(result is CapabilityResult.Done)
        assertEquals(1, mock.requestHistory.size)
        assertEquals("fresh-client", ctx.clientRegistrationStorage.getClientId(REGISTRATION_ENDPOINT))
    }

    @Test
    fun handle_recordsClientKey_inSavedRegistration() = runTest {
        // Arrange
        val mock = respondWithCreated(ClientRegistrationResponse(clientId = "fresh-client"))
        val handler = createHandler(mock)
        val ctx = createContext(REGISTRATION_ENDPOINT)

        // Act
        handler.handle(FlowNeed.ClientRegistration, ctx)

        // Assert
        val saved = ctx.clientRegistrationStorage.getRegistrationInfo(REGISTRATION_ENDPOINT)
        assertEquals(listOf(CURRENT_KID), saved?.jwks?.keys?.map { it.kid })
    }

    private fun respondWithCreated(response: ClientRegistrationResponse) = MockEngine {
        respond(
            content = Json.encodeToString(response),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun createClient(mockEngine: MockEngine): ZetaHttpClient =
        ZetaHttpClientBuilder()
            .contentNegotiation(true)
            .build(mockEngine)

    private fun createClientKey(kid: String) = Jwk(kid, "EC", "ES256", "sig", "P-256", "x", "y")

    private fun createHandler(
        mock: MockEngine,
        clientKeyKid: String = CURRENT_KID,
    ): ClientRegistrationHandler {
        val client = createClient(mock)
        val api = ClientRegistrationApiImpl(client)
        val tpm = FakeTpmProvider(false, createClientKey(clientKeyKid))

        return ClientRegistrationHandler("TestClientName", api, tpm)
    }

    private fun createContext(registrationEndpoint: String = ""): FlowContext = FlowContextImpl(
        ResourceScope("test", listOf("test")),
        FakeForwardingClient(),
        InMemoryStorage(),
        configurationStorage = FakeConfigurationStorage(registrationEndpoint),
    )

    private class FakeTpmProvider(
        private val hardwareBacked: Boolean,
        private val clientKey: Jwk = Jwk("", "", "", "", "", "", ""),
    ) : TpmProvider {
        override suspend fun isHardwareBacked(): Boolean = hardwareBacked
        override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut {
            return PublicKeyOut(byteArrayOf(1), clientKey)
        }

        override suspend fun generateDpopKey(): PublicKeyOut {
            error("not in scope of the test")
        }

        override suspend fun signWithClientKey(input: ByteArray): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun signWithSmbKeyFromBytes(input: ByteArray, keystoreBytes: ByteArray, alias: String, password: String): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun randomUuid(): Uuid {
            error("not in scope of the test")
        }

        override suspend fun getRegistrationNumber(certificate: ByteArray): String {
            error("not in scope of the test")
        }

        override suspend fun forget(resource: String?) {
            error("not in scope of the test")
        }
    }

    private class FakeConfigurationStorage(
        private val registrationEndpointValue: String = "",
    ) : ConfigurationStorage {
        override suspend fun getProtectedResource(name: String): ProtectedResourceMetadata? {
            error("not in scope of the test")
        }

        override suspend fun getProtectedResourceETag(name: String): String? {
            error("not in scope of the test")
        }

        override suspend fun saveProtectedResource(protectedRes: String, name: String, maxAgeSeconds: Long?, eTag: String?): ProtectedResourceMetadata {
            error("not in scope of the test")
        }

        override suspend fun touchProtectedResource(name: String, maxAgeSeconds: Long?, eTag: String?) {
            error("not in scope of the test")
        }

        override suspend fun getAuthServers(): List<AuthorizationServerMetadata> {
            error("not in scope of the test")
        }

        override suspend fun getAuthServer(): AuthorizationServerMetadata {
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
                registrationEndpoint = registrationEndpointValue,
            )
        }

        override suspend fun getAuthServerETag(authFqdn: String): String? {
            error("not in scope of the test")
        }

        override suspend fun saveAuthServer(metadata: AuthorizationServerMetadata, maxAgeSeconds: Long?, eTag: String?): AuthorizationServerMetadata {
            error("not in scope of the test")
        }

        override suspend fun touchAuthServer(authFqdn: String, maxAgeSeconds: Long?, eTag: String?) {
            error("not in scope of the test")
        }

        override suspend fun linkResourceToAuthorizationServer(authServerMetadata: AuthorizationServerMetadata) {
            error("not in scope of the test")
        }

        override suspend fun aslUse(): ZetaAslUse {
            error("not in scope of the test")
        }

        override suspend fun clear() {
            error("not in scope of the test")
        }

        override suspend fun invalidateDiscovery() {
            error("not in scope of the test")
        }
    }
}

/**
 * Unit tests for [ConfigurationHandler].
 */
class ConfigurationHandlerTest {

    @Test
    fun canHandle_onlyConfigurationFiles() {
        // Arrange
        val h = ConfigurationHandler(FakeApi())

        // Act & Assert
        assertTrue(h.canHandle(FlowNeed.ConfigurationFiles))
    }

    @Test
    fun canHandle_only_for_ConfigurationFiles() {
        val h = ConfigurationHandler(FakeApi(), FakeValidator())
        assertTrue(h.canHandle(FlowNeed.ConfigurationFiles))
    }

    @Test
    fun handle_returnsDone_whenMetadataAlreadyAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val protectedResourceMetadata =
            getDummyProtectedResourceObject(resource, listOf("https://auth.example.com"))
        val ctx = getDummyFlowContext()
        val authServer = getDummyAuthServerObject(resource)
        ctx.configurationStorage.saveProtectedResource(Json.encodeToString(protectedResourceMetadata), maxAgeSeconds = 10)
        ctx.configurationStorage.linkResourceToAuthorizationServer(authServer)
        val h = ConfigurationHandler(FakeApi(), FakeValidator())

        // Act
        val res = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, res)
    }

    @Test
    fun handle_doesNotFetch_whenCacheForProtectedResourceMetadataIsAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val protectedResourceMetadata = getDummyProtectedResourceObject(resource, listOf(issuer))
        val api = FakeApi(
            authJson = mapOf(issuer to Json.encodeToString(getDummyAuthServerObject(issuer))),
        )

        val h = ConfigurationHandler(api, FakeValidator())
        val ctx = getDummyFlowContext()
        ctx.configurationStorage.saveProtectedResource(Json.encodeToString(protectedResourceMetadata), maxAgeSeconds = 10)

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
    }

    @Test
    fun handle_doesNotFetch_whenCacheForAuthorizationMetadataIsAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val asMetadata = getDummyAuthServerObject(issuer)
        val api = FakeApi(
            resJson =
            mapOf(resource to Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(issuer)))),
        )

        val h = ConfigurationHandler(api, FakeValidator())
        val ctx = getDummyFlowContext()
        ctx.configurationStorage.saveAuthServer(asMetadata, maxAgeSeconds = null)
        ctx.configurationStorage.linkResourceToAuthorizationServer(asMetadata)

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
    }

    @Test
    fun handle_fetchesBothWhenNotCachedAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"

        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(issuer))),
            ),
            authJson = mapOf(issuer to Json.encodeToString(getDummyAuthServerObject(issuer))),
        )
        val h = ConfigurationHandler(api, FakeValidator())
        val ctx = getDummyFlowContext()

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
    }

    @Test
    fun handle_throwsException_whenEmptyAuthorizationServers() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, emptyList())),
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act & Assert
        val ex = assertFailsWith<ConfigurationHandler.ConfigurationError.AuthorizationServerMissing> {
            h.handle(FlowNeed.ConfigurationFiles, getDummyFlowContext())
        }
        assertTrue(ex.message!!.contains("No authorization_servers"))
    }

    @Test
    fun handle_throwsValidationException_whenSchemaValidationFails() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = false)
        val h = ConfigurationHandler(api, validator)
        val ctx = getDummyFlowContext()

        // Act & Assert
        val ex = assertFailsWith<ConfigurationHandler.ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Failed to validate"))
    }

    @Test
    fun handle_throwsException_whenValidationThrowsAnException() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = true, 0, true)
        val h = ConfigurationHandler(api, validator)
        val ctx = getDummyFlowContext()

        // Act & Assert
        val ex = assertFailsWith<ConfigurationHandler.ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Validation threw for"))
    }

    @Test
    fun handle_throwsException_whenScopeValidationThrowsAnException() = runTest {
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson = mapOf(
                resource to Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = true, 0)
        val h = ConfigurationHandler(api, validator)
        val ctx = getDummyFlowContext("missing_scope")

        val ex = assertFailsWith<ConfigurationHandler.ConfigurationError.ScopesNotSupported> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("missing_scope"))
    }
}
