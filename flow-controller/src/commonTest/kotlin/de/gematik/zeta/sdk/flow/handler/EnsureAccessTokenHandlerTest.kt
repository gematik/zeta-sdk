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
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthenticationException
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.authentication.InvalidClientException
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApi
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorage
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.configuration.models.ZetaAslUse
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowContextImpl
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.getDummyProtectedResourceObject
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.tpm.TpmProvider
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Unit tests for [EnsureAccessTokenHandler].
 */
class EnsureAccessTokenHandlerTest {
    private val handler = EnsureAccessTokenHandler(
        tokenProvider = FakeAccessTokenProvider(),
        tpmProvider = FakeTpmProvider(false),
        authConfig = AuthConfig(
            scopes = listOf("openid"),
            exp = 3600L,
            subjectTokenProvider = SubjectTokenProvider { _, _, _, _, _, _, _ -> "subject-token" },
            requiredRoleOid = "1.2.276.0.76.4.261",
        ),
        productId = "test-product",
        productVersion = "1.0.0",
        platformProductId = PlatformProductId.AndroidProductId(
            packageName = "de.gematik.test",
            sha256CertFingerprints = listOf("AA:BB:CC"),
        ),
    )

    @Test
    fun canHandle_returnsTrue_forAuthentication() {
        assertTrue(handler.canHandle(FlowNeed.Authentication))
    }

    @Test
    fun canHandle_returnsFalse_forClientRegistration() {
        assertFalse(handler.canHandle(FlowNeed.ClientRegistration))
    }

    @Test
    fun canHandle_returnsFalse_forAsl() {
        assertFalse(handler.canHandle(FlowNeed.Asl))
    }

    @Test
    fun handle_returnsRetryRequest_onSuccess() = runTest {
        val ctx = createContext()
        val result = handler.handle(FlowNeed.Authentication, ctx)
        assertTrue(result is CapabilityResult.RetryRequest)
    }

    @Test
    fun handle_mutatesRequestWithAuthorizationHeader_onSuccess() = runTest {
        val ctx = createContext()
        val result = handler.handle(FlowNeed.Authentication, ctx) as CapabilityResult.RetryRequest
        val builder = HttpRequestBuilder()
        result.mutate(builder)
        assertTrue(builder.headers.contains(HttpHeaders.Authorization))
    }

    @Test
    fun handle_mutatesRequestWithDpopHeader_onSuccess() = runTest {
        val ctx = createContext()
        val result = handler.handle(FlowNeed.Authentication, ctx) as CapabilityResult.RetryRequest
        val builder = HttpRequestBuilder()
        result.mutate(builder)
        assertTrue(builder.headers.contains(HttpAuthHeaders.Dpop))
    }

    @Test
    fun handle_hashesAccessToken_onSuccess() = runTest {
        val tokenProvider = FakeAccessTokenProvider()
        val localHandler = createHandler(tokenProvider)
        localHandler.handle(FlowNeed.Authentication, createContext())
        assertEquals(listOf("valid_token"), tokenProvider.hashCalls)
    }

    @Test
    fun handle_createsDpopWithHashedToken_onSuccess() = runTest {
        val tokenProvider = FakeAccessTokenProvider()
        val localHandler = createHandler(tokenProvider)
        val result = localHandler.handle(FlowNeed.Authentication, createContext()) as CapabilityResult.RetryRequest
        val builder = HttpRequestBuilder()
        result.mutate(builder)
        assertEquals("hashed_valid_token", tokenProvider.createDpopCalls.first().ath)
    }

    @Test
    fun handle_returnsError_onNonRecoverableFailure() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            alwaysFailWith = AuthenticationException(fakeHttpResponse(), "auth failed"),
        )
        val result = createHandler(tokenProvider).handle(FlowNeed.Authentication, createContext())
        assertTrue(result is CapabilityResult.Error)
    }

    @Test
    fun handle_returnsErrorWithMessage_onNonRecoverableFailure() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            alwaysFailWith = AuthenticationException(fakeHttpResponse(), "auth failed"),
        )
        val result = createHandler(tokenProvider).handle(FlowNeed.Authentication, createContext()) as CapabilityResult.Error
        assertEquals("auth failed", result.internalMessage)
    }

    @Test
    fun handle_returnsAuthenticationErrorCode_onNonRecoverableFailure() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            alwaysFailWith = AuthenticationException(fakeHttpResponse(), "auth failed"),
        )
        val result = createHandler(tokenProvider).handle(FlowNeed.Authentication, createContext()) as CapabilityResult.Error
        assertEquals("AUTHENTICATION_ERROR", result.internalCode)
    }

    @Test
    fun handle_returnsRetryRequest_afterStepUpSuccess() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            failFirstWith = RecoverableAuthenticationException(fakeHttpResponse(), "401"),
        )
        val result = createHandler(tokenProvider).handle(FlowNeed.Authentication, createContext())
        assertTrue(result is CapabilityResult.RetryRequest)
    }

    @Test
    fun handle_clearsAuthStorage_onStepUp() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            failFirstWith = RecoverableAuthenticationException(fakeHttpResponse(), "401"),
        )
        val storage = InMemoryStorage()
        val result = createHandler(tokenProvider).handle(FlowNeed.Authentication, createContext(storage))
        assertTrue(result is CapabilityResult.RetryRequest)
    }

    @Test
    fun handle_returnsError_whenStepUpAlsoFails() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            alwaysFailWith = RecoverableAuthenticationException(fakeHttpResponse(), "401"),
        )
        val result = createHandler(tokenProvider).handle(FlowNeed.Authentication, createContext())
        assertTrue(result is CapabilityResult.Error)
    }

    @Test
    fun handle_returnsAuthenticationErrorCode_whenStepUpAlsoFails() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            alwaysFailWith = RecoverableAuthenticationException(fakeHttpResponse(), "401"),
        )
        val result = createHandler(tokenProvider).handle(FlowNeed.Authentication, createContext()) as CapabilityResult.Error
        assertEquals("AUTHENTICATION_ERROR", result.internalCode)
    }

    @Test
    fun getValidAccessToken_returnsToken_onSuccess() = runTest {
        val result = createHandler().getValidAccessToken(createContext())
        assertEquals("valid_token", result.token)
    }

    @Test
    fun getValidAccessToken_returnsDpopKey_onSuccess() = runTest {
        val result = createHandler().getValidAccessToken(createContext())
        assertNotNull(result.dpopKey)
    }

    @Test
    fun getValidAccessToken_tokenAndDpopKeyAreBundled_onSuccess() = runTest {
        val tokenProvider = FakeAccessTokenProvider()
        val result = createHandler(tokenProvider).getValidAccessToken(createContext())
        assertEquals("valid_token", result.token)
        assertNotNull(result.dpopKey)
    }

    @Test
    fun handle_clearsClientRegistrationStorage_onStepUp() = runTest {
        // Arrange
        val tokenProvider = FakeAccessTokenProvider(
            failFirstWith = InvalidClientException(fakeHttpResponse(), "invalid_client"),
        )
        val fakeRegistrationStorage = ClearTrackingClientRegistrationStorage()
        val ctx = createContext(clientRegistrationStorage = fakeRegistrationStorage)

        // Act
        createHandler(
            tokenProvider = tokenProvider,
            clientRegistrationHandler = FakeClientRegistrationHandler(),
        ).handle(FlowNeed.Authentication, ctx)

        // Assert
        assertTrue(fakeRegistrationStorage.clearCalled)
    }

    @Test
    fun handle_doesNotClearClientRegistrationStorage_onSuccess() = runTest {
        // Arrange
        val tokenProvider = FakeAccessTokenProvider()
        val fakeRegistrationStorage = ClearTrackingClientRegistrationStorage()
        val ctx = createContext(clientRegistrationStorage = fakeRegistrationStorage)

        // Act
        createHandler(tokenProvider).handle(FlowNeed.Authentication, ctx)

        // Assert
        assertFalse(fakeRegistrationStorage.clearCalled)
    }

    @Test
    fun getValidAccessTokenWithStepUp_returnsToken_onSuccess() = runTest {
        val result = createHandler().getValidAccessTokenWithStepUp(createContext())
        assertEquals("valid_token", result.token)
    }

    @Test
    fun getValidAccessTokenWithStepUp_stepsUp_onRecoverableException() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            failFirstWith = RecoverableAuthenticationException(fakeHttpResponse(), "401"),
        )
        val result = createHandler(tokenProvider).getValidAccessTokenWithStepUp(createContext())
        assertTrue(result.token.isNotBlank())
    }

    @Test
    fun getValidAccessTokenWithStepUp_clearsAuthStorage_onInvalidClient() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            failFirstWith = InvalidClientException(fakeHttpResponse(), "invalid_client"),
        )
        val fakeRegistration = ClearTrackingClientRegistrationStorage()
        val ctx = createContext(clientRegistrationStorage = fakeRegistration)

        createHandler(
            tokenProvider = tokenProvider,
            clientRegistrationHandler = FakeClientRegistrationHandler(), // 👈
        ).getValidAccessTokenWithStepUp(ctx)

        assertTrue(fakeRegistration.clearCalled)
    }

    @Test
    fun getValidAccessTokenWithStepUp_doesNotClearRegistration_onNonInvalidClient() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            failFirstWith = RecoverableAuthenticationException(fakeHttpResponse(), "401"),
        )
        val fakeRegistration = ClearTrackingClientRegistrationStorage()
        val ctx = createContext(clientRegistrationStorage = fakeRegistration)
        createHandler(tokenProvider).getValidAccessTokenWithStepUp(ctx)
        assertFalse(fakeRegistration.clearCalled)
    }

    @Test
    fun getValidAccessTokenWithStepUp_throwsException_whenStepUpAlsoFails() = runTest {
        val tokenProvider = FakeAccessTokenProvider(
            alwaysFailWith = RecoverableAuthenticationException(fakeHttpResponse(), "401"),
        )
        assertFailsWith<RecoverableAuthenticationException> {
            createHandler(tokenProvider).getValidAccessTokenWithStepUp(createContext())
        }
    }
}

private class ClearTrackingClientRegistrationStorage : ClientRegistrationStorage {
    var clearCalled = false

    override suspend fun saveRegistration(authServer: String, registrationResponse: ClientRegistrationResponse) {}

    override suspend fun getRegistrationInfo(authServer: String): ClientRegistrationResponse =
        ClientRegistrationResponse(clientId = "fake-client-id")

    override suspend fun getClientId(authServer: String): String = "fake-client-id"

    override suspend fun clear() {
        clearCalled = true
    }
}

private class FakeTpmProvider(private val hardwareBacked: Boolean) : TpmProvider {
    override suspend fun isHardwareBacked(): Boolean = hardwareBacked
    override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut {
        return PublicKeyOut(byteArrayOf(1), Jwk("", "", "", "", "", "", ""))
    }

    override suspend fun generateDpopKey(): PublicKeyOut {
        return PublicKeyOut(byteArrayOf(), Jwk("kid", "EC", "ES256", "sig", "P-256", "x", "y"))
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

private class FakeAccessTokenProvider(
    private val failFirstWith: AuthenticationException? = null,
    private val alwaysFailWith: AuthenticationException? = null,
) : AccessTokenProvider {
    val hashCalls = mutableListOf<String>()
    val createDpopCalls = mutableListOf<CreateDpopCall>()
    private var callCount = 0

    override suspend fun getValidToken(
        tokenEndpoint: String,
        nonceEndpoint: String,
        params: AccessTokenParams,
        dpopKey: String,
    ): String {
        callCount++
        alwaysFailWith?.let { throw it }
        if (callCount == 1) failFirstWith?.let { throw it }
        return "valid_token"
    }

    override suspend fun createDpopToken(dpopKey: Jwk, method: String, url: String, nonceBytes: ByteArray?, accessTokenHash: String?): String {
        createDpopCalls.add(CreateDpopCall(method, url, null, accessTokenHash))
        return "dpop_token"
    }

    override suspend fun hash(token: String): String {
        hashCalls.add(token)
        return "hashed_$token"
    }

    data class CreateDpopCall(val method: String, val url: String, val nonce: String?, val ath: String?)
}

private fun createHandler(tokenProvider: AccessTokenProvider = FakeAccessTokenProvider(), clientRegistrationHandler: ClientRegistrationHandler? = null) =
    EnsureAccessTokenHandler(
        tokenProvider = tokenProvider,
        tpmProvider = FakeTpmProvider(false),
        authConfig = AuthConfig(
            scopes = listOf("openid"),
            exp = 3600L,
            subjectTokenProvider = SubjectTokenProvider { _, _, _, _, _, _, _ -> "subject-token" },
            requiredRoleOid = "1.2.276.0.76.4.261",
        ),
        productId = "test-product",
        productVersion = "1.0.0",
        platformProductId = PlatformProductId.AndroidProductId(
            packageName = "de.gematik.test",
            sha256CertFingerprints = listOf("AA:BB:CC"),
        ),
        clientRegistrationHandler = clientRegistrationHandler,
    )

private class FakeForwardingClient : de.gematik.zeta.sdk.flow.ForwardingClient {
    override suspend fun executeOnce(builder: HttpRequestBuilder): ZetaHttpResponse {
        TODO("Not yet implemented")
    }
}

class FakeConfigurationStorage : ConfigurationStorage {
    private val notInScope: String = "not in scope"
    override suspend fun getProtectedResource(name: String): ProtectedResourceMetadata =
        getDummyProtectedResourceObject()

    override suspend fun getProtectedResourceETag(name: String): String? =
        error(notInScope)

    override suspend fun saveProtectedResource(protectedRes: String, name: String, maxAgeSeconds: Long?, eTag: String?): ProtectedResourceMetadata =
        error(notInScope)

    override suspend fun touchProtectedResource(name: String, maxAgeSeconds: Long?, eTag: String?) =
        error(notInScope)

    override suspend fun getAuthServers(): List<AuthorizationServerMetadata> =
        error(notInScope)

    override suspend fun getAuthServer(): AuthorizationServerMetadata =
        AuthorizationServerMetadata(
            issuer = "https://auth.example.com",
            authorizationEndpoint = "",
            tokenEndpoint = "https://auth.example.com/token",
            nonceEndpoint = "https://auth.example.com/nonce",
            openidProvidersEndpoint = "",
            jwksUri = "",
            scopesSupported = listOf("openid"),
            responseTypesSupported = listOf("TOKEN"),
            responseModesSupported = listOf(""),
            grantTypesSupported = listOf(""),
            tokenEndpointAuthMethodsSupported = listOf(""),
            tokenEndpointAuthSigningAlgValuesSupported = listOf(""),
            serviceDocumentation = "",
            uiLocalesSupported = listOf(""),
            codeChallengeMethodsSupported = listOf(""),
            registrationEndpoint = "",

        )

    override suspend fun getAuthServerETag(authFqdn: String): String? =
        error(notInScope)

    override suspend fun saveAuthServer(metadata: AuthorizationServerMetadata, maxAgeSeconds: Long?, eTag: String?): AuthorizationServerMetadata {
        error(notInScope)
    }

    override suspend fun touchAuthServer(authFqdn: String, maxAgeSeconds: Long?, eTag: String?) =
        error(notInScope)

    override suspend fun linkResourceToAuthorizationServer(authServerMetadata: AuthorizationServerMetadata) =
        error(notInScope)

    override suspend fun aslUse(): ZetaAslUse = error(notInScope)
    override suspend fun clear() = error(notInScope)
    override suspend fun invalidateDiscovery() =
        error(notInScope)
}

class FakeClientRegistrationStorage : ClientRegistrationStorage {
    override suspend fun saveRegistration(authServer: String, registrationResponse: ClientRegistrationResponse) {
        TODO("Not yet implemented")
    }

    override suspend fun getRegistrationInfo(authServer: String): ClientRegistrationResponse? {
        TODO("Not yet implemented")
    }

    override suspend fun getClientId(authServer: String): String = "fake-client-id"
    override suspend fun clear() {}
}

private fun fakeHttpResponse(status: HttpStatusCode = HttpStatusCode.Unauthorized): HttpResponse {
    val engine = MockEngine { respond("", status) }
    return runBlocking {
        io.ktor.client.HttpClient(engine).get("http://localhost")
    }
}

fun createContext(
    storage: SdkStorage = InMemoryStorage(),
    clientRegistrationStorage: ClientRegistrationStorage = FakeClientRegistrationStorage(),
) = FlowContextImpl(
    resourceScope = ResourceScope("https://resource.example.com", listOf("scope")),
    client = FakeForwardingClient(),
    storage = storage,
    configurationStorage = FakeConfigurationStorage(),
    clock = SystemZetaClock,
    clientRegistrationStorage = clientRegistrationStorage,
)

private class FakeClientRegistrationHandler : ClientRegistrationHandler(
    clientName = "test",
    regApi = object : ClientRegistrationApi {
        override suspend fun register(
            endpoint: String,
            request: ClientRegistrationRequest,
        ): ClientRegistrationResponse = ClientRegistrationResponse(clientId = "fake-id")
    },
    tpmProvider = FakeTpmProvider(false),
) {
    override suspend fun handle(need: FlowNeed, ctx: FlowContext): CapabilityResult = CapabilityResult.Done
}
