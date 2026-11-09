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
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.DeviceAttestationEvidence
import de.gematik.zeta.sdk.attestation.DeviceAttestor
import de.gematik.zeta.sdk.attestation.HardwareAttestationType
import de.gematik.zeta.sdk.attestation.RegisteredAttestationType
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.ApiVersionStatus
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
import de.gematik.zeta.sdk.tpm.ClientKeyPoPSigner
import de.gematik.zeta.sdk.tpm.TpmProvider
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import joseToDerEcdsa
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ClientRegistrationHandlerTest {
    private val MAX_RETRIES = 3
    private val CURRENT_KID = "current-client-key"
    private val REGISTRATION_ENDPOINT = "https://as.example.com/register"

    private companion object {
        const val NONCE_ENDPOINT = "https://as.example.com/nonce"

        /** JOSE (r || s) signature the fake client key returns. */
        val SIGNATURE = ByteArray(64) { 1 }

        // RFC 7517 A.1 example coordinates: the software proof decodes them, so they must be real base64url.
        const val CLIENT_KEY_X = "MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4"
        const val CLIENT_KEY_Y = "4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM"

        /** The n-th nonce the fake nonce endpoint hands out: 16 bytes of n, base64url like the guard's. */
        fun nonce(n: Int) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(ByteArray(16) { n.toByte() })
    }

    @Test
    fun handle_success_onFirstAttempt() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")

        val mock = registrationEngine {
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

        val mock = registrationEngine {
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
        assertEquals(MAX_RETRIES - 1, registrationRequests(mock).size)
    }

    @Test
    fun handle_retriesUnauthorized() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        var callCount = 0

        val mock = registrationEngine {
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
        assertEquals(MAX_RETRIES, registrationRequests(mock).size)
    }

    @Test
    fun handle_returnsBadRequest_whenRetriesIsReached() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = registrationEngine {
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
        assertEquals(MAX_RETRIES, registrationRequests(mock).size)
    }

    @Test
    fun handle_failsWithoutRetry_onStatus403() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = registrationEngine {
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
        assertEquals(1, registrationRequests(mock).size)
    }

    @Test
    fun handle_failsWithoutRetry_onStatus409() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = registrationEngine {
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
        assertEquals(1, registrationRequests(mock).size)
    }

    @Test
    fun handle_retries3Times_withMaxRetry3() = runTest {
        // Arrange
        val response = ClientRegistrationResponse(clientId = "")
        val mock = registrationEngine {
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
        assertEquals(3, registrationRequests(mock).size)
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
        assertEquals(1, registrationRequests(mock).size)
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
        assertEquals(0, registrationRequests(mock).size)
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
        assertEquals(1, registrationRequests(mock).size)
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

    @Test
    fun software_sendsNonce_andProofOfPossessionOverGuardMessage() = runTest {
        val tpm = FakeTpmProvider(false, createClientKey(CURRENT_KID))
        val engine = respondWithCreated(ClientRegistrationResponse(clientId = "cid"))
        val handler = ClientRegistrationHandler("TestClientName", ClientRegistrationApiImpl(createClient(engine)), tpm)

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)

        val body = bodyOf(engine, 0)
        val expectedSignature = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(joseToDerEcdsa(SIGNATURE))
        assertTrue(body.contains("\"attestation_type\":\"software\""))
        assertTrue(body.contains("\"nonce\":\"${nonce(1)}\""))
        assertTrue(body.contains("\"signed_hash_puk_client_sig\":\"$expectedSignature\""))
        assertContentEquals(softwareProofMessage(createClientKey(CURRENT_KID), nonce(1)), tpm.signedInputs.single())
    }

    @Test
    fun software_fetchesFreshNonce_forEveryAttempt() = runTest {
        // the guard uses up a nonce even when it rejects the request, so a retry must not reuse it
        val tpm = FakeTpmProvider(false, createClientKey(CURRENT_KID))
        val engine = scripted("{}" to HttpStatusCode.BadRequest, created to HttpStatusCode.Created)
        val handler = ClientRegistrationHandler("TestClientName", ClientRegistrationApiImpl(createClient(engine)), tpm)

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)

        assertTrue(bodyOf(engine, 0).contains("\"nonce\":\"${nonce(1)}\""))
        assertTrue(bodyOf(engine, 1).contains("\"nonce\":\"${nonce(2)}\""))
        assertContentEquals(softwareProofMessage(createClientKey(CURRENT_KID), nonce(2)), tpm.signedInputs.last())
        assertEquals(2, tpm.signedInputs.size)
    }

    @Test
    fun software_institutionClient_omitsRedirectUris_andAuthorizationCode() = runTest {
        val engine = respondWithCreated(ClientRegistrationResponse(clientId = "cid"))

        createHandler(engine).handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT))

        val body = bodyOf(engine, 0)
        assertFalse(body.contains("redirect_uris"))
        assertFalse(body.contains("response_types"))
        assertTrue(body.contains("\"grant_types\":[\"urn:ietf:params:oauth:grant-type:token-exchange\",\"refresh_token\"]"))
    }

    @Test
    fun software_mobileClient_sendsRedirectUris_andAuthorizationCode() = runTest {
        val engine = respondWithCreated(ClientRegistrationResponse(clientId = "cid"))
        val redirectUris = listOf("https://app.example.com/cb/app/app", "https://app.example.com/cb/app/oidc")
        val handler = ClientRegistrationHandler(
            "TestClientName", ClientRegistrationApiImpl(createClient(engine)), FakeTpmProvider(false, createClientKey(CURRENT_KID)),
            redirectUris = redirectUris,
        )

        handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT))

        val body = bodyOf(engine, 0)
        assertTrue(body.contains("\"redirect_uris\":[\"https://app.example.com/cb/app/app\",\"https://app.example.com/cb/app/oidc\"]"))
        assertTrue(body.contains("\"grant_types\":[\"authorization_code\",\"urn:ietf:params:oauth:grant-type:token-exchange\",\"refresh_token\"]"))
        assertFalse(body.contains("response_types"))
    }

    @Test
    fun software_failsRegistration_whenNoNonceCanBeFetched() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }

        val result = createHandler(engine).handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT))

        assertTrue(result is CapabilityResult.Error)
        assertEquals(MAX_RETRIES, engine.requestHistory.count { it.url.toString() == NONCE_ENDPOINT })
        assertTrue(registrationRequests(engine).isEmpty())
    }

    private fun respondWithCreated(response: ClientRegistrationResponse) = registrationEngine {
        respond(
            content = Json.encodeToString(response),
            status = HttpStatusCode.Created,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    /** Answers the nonce endpoint itself (nonce(1), nonce(2), ...) and hands every other request to [register]. */
    private fun registrationEngine(register: MockRequestHandler): MockEngine {
        var nonces = 0
        return MockEngine { request ->
            if (request.method == HttpMethod.Get && request.url.toString() == NONCE_ENDPOINT) {
                nonces++
                respond(nonce(nonces), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
            } else {
                register(request)
            }
        }
    }

    private fun registrationRequests(engine: MockEngine) = engine.requestHistory.filter { it.method == HttpMethod.Post }

    private fun createClient(mockEngine: MockEngine): ZetaHttpClient =
        ZetaHttpClientBuilder()
            .contentNegotiation(true)
            .build(mockEngine)

    private fun createClientKey(kid: String) = Jwk(kid, "EC", "ES256", "sig", "P-256", CLIENT_KEY_X, CLIENT_KEY_Y)

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
        clock = SystemZetaClock,
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

        val signedInputs = mutableListOf<ByteArray>()

        override suspend fun signWithClientKey(input: ByteArray): ByteArray {
            signedInputs += input
            return SIGNATURE
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
        override suspend fun getProtectedResource(name: String, allowStale: Boolean): ProtectedResourceMetadata? {
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

        override suspend fun getAuthServer(allowStale: Boolean): AuthorizationServerMetadata {
            return AuthorizationServerMetadata(
                issuer = "issuer",
                authorizationEndpoint = "",
                tokenEndpoint = "token_endpoint",
                nonceEndpoint = NONCE_ENDPOINT,
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
                apiVersionsSupported = listOf(ApiVersion(majorVersion = 2, version = "2.0.0", status = ApiVersionStatus.STABLE)),
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

    // ---- hardware / TOFU / fast-path ----

    private val created = """{"client_id":"cid"}"""
    private val clientPoint = ByteArray(65) { if (it == 0) 0x04 else 1 }

    /** Scripted engine: one response per registration request, in order; the last one repeats. */
    private fun scripted(vararg responses: Pair<String, HttpStatusCode>): MockEngine {
        var i = 0
        return registrationEngine { _ ->
            val (body, status) = responses[minOf(i, responses.lastIndex)]
            i++
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
    }

    private fun bodyOf(engine: MockEngine, index: Int) = (registrationRequests(engine)[index].body as TextContent).text
    private fun lastBody(engine: MockEngine) = (registrationRequests(engine).last().body as TextContent).text

    private class FakeDeviceAttestor(
        private val supported: Boolean = true,
        private val attested: Boolean = false,
        private val evidence: DeviceAttestationEvidence = DeviceAttestationEvidence.Apple("key-1", byteArrayOf(0x0A, 0x0B)),
        private val throwOnAttest: Throwable? = null,
        private val throwOnProve: Throwable? = null,
        override val attestationType: HardwareAttestationType = HardwareAttestationType.APPLE,
        private val state: AttestationStateStorage? = null,
    ) : DeviceAttestor {
        var attestCalledWith: ByteArray? = null
        var forgetCalls = 0
        var keyIdAtForget: String? = null
        var proveCalledWith: ByteArray? = null
        var attestCalls = 0
        override fun isSupported() = supported
        override suspend fun attest(pukClientSig: ByteArray): DeviceAttestationEvidence {
            attestCalls++
            attestCalledWith = pukClientSig
            throwOnAttest?.let { throw it }
            return evidence
        }
        override suspend fun runtimeEvidence(keyId: String, clientDataHash: ByteArray): ByteArray = error("not used")
        override suspend fun proveAttestationKey(hashPukClientSig: ByteArray): ByteArray {
            proveCalledWith = hashPukClientSig
            throwOnProve?.let { throw it }
            return byteArrayOf(0x50, 0x4F, 0x50)
        }
        override suspend fun hasAttestedKey() = attested
        override suspend fun forget() {
            forgetCalls++
            keyIdAtForget = state?.getKeyId()
        }
    }

    private class FakePoPTpmProvider(private val clientPoint: ByteArray, kid: String = "current-client-key", hardwareBacked: Boolean = true) :
        TpmProvider by FakeTpmProvider(hardwareBacked, Jwk(kid, "EC", "ES256", "sig", "P-256", CLIENT_KEY_X, CLIENT_KEY_Y)), ClientKeyPoPSigner {
        override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut =
            PublicKeyOut(clientPoint, Jwk("current-client-key", "EC", "ES256", "sig", "P-256", CLIENT_KEY_X, CLIENT_KEY_Y))
        override suspend fun signProofOfPossession(): ByteArray = byteArrayOf(0x30, 0x06)
    }

    private fun hardwareHandler(
        engine: MockEngine,
        attestor: DeviceAttestor? = FakeDeviceAttestor(),
        state: AttestationStateStorage = AttestationStateStorage(InMemoryStorage()),
        tpm: TpmProvider = FakePoPTpmProvider(clientPoint),
        popSigner: ClientKeyPoPSigner? = tpm as? ClientKeyPoPSigner,
    ) = ClientRegistrationHandler(
        "TestClientName", ClientRegistrationApiImpl(createClient(engine)), tpm, emptyList(), MAX_RETRIES,
        deviceAttestor = attestor, attestationState = state, popSigner = popSigner,
    )

    @Test
    fun hardware_sendsAppleRequest_andRecordsAppleType_on201() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        val attestor = FakeDeviceAttestor()
        val handler = hardwareHandler(engine, attestor, state = state)

        val result = handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT))

        assertTrue(result is CapabilityResult.Done)
        assertContentEquals(clientPoint, attestor.attestCalledWith)
        val body = bodyOf(engine, 0)
        assertTrue(body.contains("\"attestation_type\":\"apple\""))
        assertTrue(body.contains("\"apple_attestation_object\":\"${Base64.encode(byteArrayOf(0x0A, 0x0B))}\""))
        assertFalse(body.contains("user_email"))
        assertTrue(body.contains("\"signed_hash_puk_client_sig\":\"MAY\""))
        assertEquals(RegisteredAttestationType.APPLE, state.getAttestationType())
    }

    @Test
    fun hardware_sendsAndroidRequest_andRecordsAndroidType_on201() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        val attestor = FakeDeviceAttestor(
            evidence = DeviceAttestationEvidence.Android(listOf(byteArrayOf(0x0A), byteArrayOf(0x0B), byteArrayOf(0x0C))),
            attestationType = HardwareAttestationType.ANDROID,
        )
        val handler = hardwareHandler(engine, attestor, state = state)

        val result = handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT))

        assertTrue(result is CapabilityResult.Done)
        assertContentEquals(clientPoint, attestor.attestCalledWith)
        val body = bodyOf(engine, 0)
        assertTrue(body.contains("\"attestation_type\":\"android\""))
        assertTrue(body.contains("\"android_key_attestation_certificate_chain\":[\"Cg==\",\"Cw==\",\"DA==\"]"))
        assertTrue(body.contains("\"signed_hash_puk_client_sig\":\"MAY\""))
        assertEquals(RegisteredAttestationType.ANDROID, state.getAttestationType())
    }

    @Test
    fun hardware_persistsStatus_fromCreatedResponse() = runTest {
        val engine = scripted("""{"client_id":"cid","status":"pending_user_binding"}""" to HttpStatusCode.Created)
        val handler = hardwareHandler(engine)
        val ctx = createContext(REGISTRATION_ENDPOINT)

        assertTrue(handler.handle(FlowNeed.ClientRegistration, ctx) is CapabilityResult.Done)
        assertEquals("pending_user_binding", ctx.clientRegistrationStorage.getRegistrationInfo(REGISTRATION_ENDPOINT)?.status)
    }

    @Test
    fun hardware_202_isRegistrationError_notPending() = runTest {
        val accepted = """{"transaction_id":"tx-1","status":"pending_verification","challenge_type":"email_otp"}"""
        val engine = scripted(accepted to HttpStatusCode.Accepted)
        val state = AttestationStateStorage(InMemoryStorage())
        val handler = hardwareHandler(engine, state = state)

        val result = handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT))

        assertEquals("REGISTRATION_FAILED_ERROR", (result as CapabilityResult.Error).internalCode)
        assertEquals(1, registrationRequests(engine).size)
        assertNull(state.getAttestationType())
    }

    @Test
    fun hardware_fallsBackToSoftware_whenAttestorUnsupported() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        val handler = hardwareHandler(engine, FakeDeviceAttestor(supported = false), state = state)

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertTrue(bodyOf(engine, 0).contains("attestation_type"))
        assertEquals(RegisteredAttestationType.SOFTWARE, state.getAttestationType())
    }

    @Test
    fun hardware_fallsBackToSoftware_whenAttestThrows() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        val handler = hardwareHandler(engine, FakeDeviceAttestor(throwOnAttest = RuntimeException("attest boom")), state = state)
        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertTrue(bodyOf(engine, 0).contains("attestation_type"))
        assertEquals(RegisteredAttestationType.SOFTWARE, state.getAttestationType())
    }

    @Test
    fun hardware_fallsBackToSoftware_whenNoAttestationState() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val attestor = FakeDeviceAttestor()
        val tpm = FakePoPTpmProvider(clientPoint)
        val handler = ClientRegistrationHandler(
            "TestClientName", ClientRegistrationApiImpl(createClient(engine)), tpm,
            emptyList(), MAX_RETRIES,
            deviceAttestor = attestor, attestationState = null, popSigner = tpm,
        )

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertTrue(bodyOf(engine, 0).contains("attestation_type"))
        assertNull(attestor.attestCalledWith)
    }

    @Test
    fun hardware_fallsBackToSoftware_whenNoPoPSigner() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val handler = hardwareHandler(engine, popSigner = null)
        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertTrue(bodyOf(engine, 0).contains("attestation_type"))
    }

    @Test
    fun hardware_guardRejectsAttestation400_failsRegistration_andDiscardsKey() = runTest {
        val rejected = """{"error":"invalidAttestation"}""" to HttpStatusCode.BadRequest
        val engine = scripted(rejected, created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        state.setKeyId("key-1")
        state.setAttestationObject("b2JqZWN0")
        val attestor = FakeDeviceAttestor(state = state)
        val handler = hardwareHandler(engine, attestor, state = state)

        val result = handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT))

        assertEquals("REGISTRATION_FAILED_ERROR", (result as CapabilityResult.Error).internalCode)
        assertEquals(1, registrationRequests(engine).size)
        assertTrue(bodyOf(engine, 0).contains("\"attestation_type\":\"apple\""))
        assertEquals(1, attestor.forgetCalls)
        assertEquals("key-1", attestor.keyIdAtForget)
        assertNull(state.getKeyId())
        assertNull(state.getAttestationObject())
        assertNull(state.getAttestationType())
    }

    @Test
    fun hardware_afterGuardRejection_attestsAgain_onNextRequest() = runTest {
        val rejected = """{"error":"invalidAttestation"}""" to HttpStatusCode.BadRequest
        val engine = scripted(rejected, created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        val attestor = FakeDeviceAttestor()
        val handler = hardwareHandler(engine, attestor, state = state)
        val ctx = createContext(REGISTRATION_ENDPOINT)

        val first = handler.handle(FlowNeed.ClientRegistration, ctx)
        val second = handler.handle(FlowNeed.ClientRegistration, ctx)

        assertEquals("REGISTRATION_FAILED_ERROR", (first as CapabilityResult.Error).internalCode)
        assertEquals(CapabilityResult.Done, second)
        assertEquals(2, registrationRequests(engine).size)
        assertEquals(2, attestor.attestCalls)
        assertTrue(bodyOf(engine, 1).contains("\"attestation_type\":\"apple\""))
    }

    @Test
    fun hardware_skipsAttestation_whenTpmIsNotHardwareBacked() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        val attestor = FakeDeviceAttestor()
        val handler = hardwareHandler(engine, attestor, state = state, tpm = FakePoPTpmProvider(clientPoint, hardwareBacked = false))

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertNull(attestor.attestCalledWith)
        assertTrue(bodyOf(engine, 0).contains("attestation_type"))
        assertEquals(RegisteredAttestationType.SOFTWARE, state.getAttestationType())
    }

    @Test
    fun fastPath_usesAttestationToken_whenPresentAndKeyAttested() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        state.setZetaAttestationToken("zat-jwt")
        val attestor = FakeDeviceAttestor(attested = true)
        val handler = hardwareHandler(engine, attestor, state = state)

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        val body = bodyOf(engine, 0)
        assertTrue(body.contains("\"attestation_type\":\"zeta_attestation_token\""))
        assertTrue(body.contains("\"zeta_attestation_token\":\"zat-jwt\""))
        assertTrue(body.contains("\"attestation_pop\":\"${Base64.encode(byteArrayOf(0x50, 0x4F, 0x50))}\""))
        assertFalse(body.contains("user_email"))
        assertContentEquals(de.gematik.zeta.sdk.crypto.sha256PublicKeyPoint(clientPoint), attestor.proveCalledWith)
        assertEquals(RegisteredAttestationType.APPLE, state.getAttestationType())
    }

    @Test
    fun fastPath_401_dropsToken_andRunsFullHardwarePath() = runTest {
        // a 401 on the fast-path is deterministic: no retry, straight to the full hardware path
        val unauthorized = "{}" to HttpStatusCode.Unauthorized
        val engine = scripted(unauthorized, created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        state.setZetaAttestationToken("zat-jwt")
        val handler = hardwareHandler(engine, FakeDeviceAttestor(attested = true), state = state)

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertEquals(2, registrationRequests(engine).size)
        assertTrue(bodyOf(engine, 0).contains("\"attestation_type\":\"zeta_attestation_token\""))
        assertTrue(lastBody(engine).contains("\"attestation_type\":\"apple\""))
        assertNull(state.getZetaAttestationToken())
    }

    @Test
    fun fastPath_skipped_whenNoAttestedKey() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        state.setZetaAttestationToken("zat-jwt")
        val handler = hardwareHandler(engine, FakeDeviceAttestor(attested = false), state = state)

        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertTrue(bodyOf(engine, 0).contains("\"attestation_type\":\"apple\""))
    }

    @Test
    fun software_recordsSoftwareType_whenNoAttestorConfigured() = runTest {
        val engine = scripted(created to HttpStatusCode.Created)
        val state = AttestationStateStorage(InMemoryStorage())
        val handler = hardwareHandler(engine, attestor = null, state = state)
        assertTrue(handler.handle(FlowNeed.ClientRegistration, createContext(REGISTRATION_ENDPOINT)) is CapabilityResult.Done)
        assertEquals(RegisteredAttestationType.SOFTWARE, state.getAttestationType())
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
