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

package de.gematik.zeta.sdk.asl

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import de.gematik.zeta.sdk.crypto.EcdhP256Kem
import de.gematik.zeta.sdk.crypto.KeyPair
import de.gematik.zeta.sdk.crypto.ML768Kem
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.tpm.TpmProvider
import de.gematik.zeta.time.SystemZetaClock
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.headersOf
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AslHandshakeStateTest {
    private val oid = "1.2.276.0.76.4.261"
    private val clock: ZetaClock = SystemZetaClock
    private val revStorage =
        RevocationChecker(RevocationStorage(InMemoryStorage(), ResourceScope("", listOf())), ZetaHttpClient(HttpClient {}), clock = clock)

    @Test
    fun create_initializesState_validParameters() {
        // Arrange
        val httpClient = ZetaHttpClient(HttpClient())
        val request = buildRequest()
        val tokenProvider = FakeAccessTokenProvider()
        val fakeTpm = FakeTpmProvider(false)
        // Act
        val result = AslHandshakeState.create(
            httpClient, request, tokenProvider, fakeTpm, false,
            AslDependencies(AslStorageImpl(InMemoryStorage(), ResourceScope("", emptyList())), revStorage, clock = clock),
        )

        // Assert
        assertEquals(request, result.request)
        assertEquals(httpClient, result.httpClient)
        assertEquals(tokenProvider, result.accessTokenProvider)
        assertNotNull(result.mlKem)
        assertNotNull(result.ecdhKem)
        assertNull(result.message1)
        assertNull(result.message1Result)
        assertNull(result.m3Result)
        assertNull(result.m3Encoded)
        assertNull(result.transcriptHash)
        assertNull(result.message4)
    }

    @Test
    fun processMessage2AndBuildMessage3_throwsIllegalArgumentException_message1Null() = runTest {
        // Arrange
        val state = buildState(message1 = null, message1Result = null)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            state.processMessage2AndBuildMessage3(false, oid)
        }
    }

    @Test
    fun processMessage2AndBuildMessage3_throwsIllegalArgumentException_message1ResultNull() = runTest {
        // Arrange
        val message1 = Message1Bundle(
            encoded = ByteArray(0),
            VauPairKeys(
                KeyPair(ByteArray(0), ByteArray(0), ByteArray(0)),
                KeyPair(ByteArray(0), ByteArray(0), ByteArray(0)),
            ),
        )
        val state = buildState(message1 = message1, message1Result = null)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            state.processMessage2AndBuildMessage3(false, oid)
        }
    }

    @Test
    fun processMessage2AndBuildMessage3_usesSystemClock_byDefault() = runTest {
        val message1 = Message1Bundle(
            encoded = ByteArray(0),
            keys = VauPairKeys(
                ecdhKey = KeyPair(
                    skpi = ByteArray(65),
                    sec1 = null,
                    privateKey = ByteArray(32),
                ),
                ml768Key = KeyPair(
                    skpi = ByteArray(1184),
                    sec1 = null,
                    privateKey = ByteArray(32),
                ),
            ),
        )

        val state = buildState(
            message1 = message1,
            message1Result = Message1Result(
                cid = "test-cid",
                response = ByteArray(0),
                transcript = ByteArray(0),
            ),
        )

        assertFailsWith<Exception> {
            state.processMessage2AndBuildMessage3(
                aslProdEnvironment = false,
                requiredRoleOid = oid,
            )
        }
    }

    @Test
    fun sendMessage3AndReceiveMessage4_throwsIllegalArgumentException_message1ResultNull() = runTest {
        // Arrange
        val state = buildState(message1Result = null, m3Encoded = ByteArray(0))

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            state.sendMessage3AndReceiveMessage4()
        }
    }

    @Test
    fun sendMessage3AndReceiveMessage4_throwsIllegalArgumentException_m3EncodedNull() = runTest {
        // Arrange
        val message1Result = Message1Result(
            cid = "test-cid",
            response = ByteArray(0),
            transcript = ByteArray(0),
        )
        val state = buildState(message1Result = message1Result, m3Encoded = null)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            state.sendMessage3AndReceiveMessage4()
        }
    }

    @Test
    fun validateMessage4AndEstablishSession_throwsIllegalArgumentException_m3ResultNull() {
        // Arrange
        val state = buildState(
            m3Result = null,
            message4 = Message4("", ByteArray(0)),
            transcriptHash = ByteArray(0),
        )

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            state.validateMessage4AndEstablishSession(aslProdEnvironment = true)
        }
    }

    @Test
    fun validateMessage4AndEstablishSession_throwsIllegalArgumentException_message4Null() {
        // Arrange
        val m3Result = Message3Result(
            k2 = K2Keys(
                keyId = ByteArray(32),
                clientToServerAppDataKey = ByteArray(32),
                serverToClientAppDataKey = ByteArray(32),
                clientToServerConfirmationKey = ByteArray(32),
                serverToClientConfirmationKey = ByteArray(32),
                outputKeyingMaterial160 = ByteArray(32),
            ),
            m3Encoded = ByteArray(0),
            expectedTranscriptHash = ByteArray(0),
        )
        val state = buildState(
            m3Result = m3Result,
            message4 = null,
            transcriptHash = ByteArray(0),
        )

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            state.validateMessage4AndEstablishSession(aslProdEnvironment = true)
        }
    }

    @Test
    fun validateMessage4AndEstablishSession_throwsIllegalArgumentException_transcriptHashNull() {
        // Arrange
        val m3Result = Message3Result(
            k2 = K2Keys(
                keyId = ByteArray(32),
                clientToServerAppDataKey = ByteArray(32),
                serverToClientAppDataKey = ByteArray(32),
                clientToServerConfirmationKey = ByteArray(32),
                serverToClientConfirmationKey = ByteArray(32),
                outputKeyingMaterial160 = ByteArray(32),
            ),
            m3Encoded = ByteArray(0),
            expectedTranscriptHash = ByteArray(0),
        )
        val state = buildState(
            m3Result = m3Result,
            message4 = Message4("", ByteArray(0)),
            transcriptHash = null,
        )

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            state.validateMessage4AndEstablishSession(aslProdEnvironment = true)
        }
    }

    @Test
    fun applyDpopFor_throwsIllegalStateException_noAuthHeader() {
        // Arrange
        val request = buildRequest(authHeader = null)
        val state = buildState(request = request)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            runBlocking { state.applyDpopFor("POST", "https://example.com/resource") }
        }
    }

    @Test
    fun applyDpopFor_callsAccessTokenProvider_validAuthHeader() = runBlocking {
        // Arrange
        val tokenProvider = FakeAccessTokenProvider()
        val authHeader = "${HttpAuthHeaders.Dpop} test_token"
        val request = buildRequest(authHeader = authHeader)
        val state = buildState(request = request).copy(accessTokenProvider = tokenProvider)

        // Act
        val result = state.applyDpopFor("POST", "https://example.com/resource")

        // Assert
        assertEquals("dpop_token", result)
        assertEquals(listOf("test_token"), tokenProvider.hashCalls)
        assertEquals(1, tokenProvider.createDpopCalls.size)
        val call = tokenProvider.createDpopCalls.first()
        assertEquals("POST", call.method)
        assertEquals("https://example.com/resource", call.url)
        assertNull(call.nonce)
        assertEquals("hashed_test_token", call.ath)
    }

    @Test
    fun applyDpopFor_trimsToken_authHeaderWithWhitespace() = runBlocking {
        // Arrange
        val tokenProvider = FakeAccessTokenProvider()
        val authHeader = "${HttpAuthHeaders.Dpop}   token_with_spaces   "
        val request = buildRequest(authHeader = authHeader)
        val state = buildState(request = request).copy(accessTokenProvider = tokenProvider)

        // Act
        state.applyDpopFor("GET", "https://example.com")

        // Assert
        assertEquals(listOf("token_with_spaces"), tokenProvider.hashCalls)
    }

    @Test
    fun aslUrl_usesDefaultPath_cidNull() {
        // Arrange
        val url = URLBuilder().apply {
            protocol = URLProtocol.HTTPS
            host = "api.example.com"
            port = 443
        }

        // Act
        val result = aslUrl(url, cid = null)

        // Assert
        assertEquals("https://api.example.com/ASL", result)
    }

    @Test
    fun aslUrl_usesCidAsPath_cidProvided() {
        // Arrange
        val url = URLBuilder().apply {
            protocol = URLProtocol.HTTPS
            host = "api.example.com"
            port = 443
        }

        // Act
        val result = aslUrl(url, cid = "/ASL/custom-cid-123")

        // Assert
        assertEquals("https://api.example.com/ASL/custom-cid-123", result)
    }

    @Test
    fun aslUrl_preservesPort_nonDefaultPort() {
        // Arrange
        val url = URLBuilder().apply {
            protocol = URLProtocol.HTTPS
            host = "api.example.com"
            port = 8443
        }

        // Act
        val result = aslUrl(url, cid = null)

        // Assert
        assertEquals("https://api.example.com:8443/ASL", result)
    }

    @Test
    fun aslUrl_usesHttp_httpProtocol() {
        // Arrange
        val url = URLBuilder().apply {
            protocol = URLProtocol.HTTP
            host = "localhost"
            port = 8080
        }

        // Act
        val result = aslUrl(url, cid = "/ASL/test")

        // Assert
        assertEquals("http://localhost:8080/ASL/test", result)
    }

    @Test
    fun validateMessage4AndEstablishSession_throwsIllegalArgumentException_message1ResultNull() {
        val m3Result = buildM3Result()
        val state = buildState(
            m3Result = m3Result,
            message4 = Message4("", ByteArray(0)),
            transcriptHash = ByteArray(0),
            message1Result = null,
        )

        assertFailsWith<IllegalArgumentException> {
            state.validateMessage4AndEstablishSession(aslProdEnvironment = true)
        }
    }

    @Test
    fun create_initializesState_tlsValidationTrue() {
        val httpClient = ZetaHttpClient(HttpClient())
        val request = buildRequest()
        val tokenProvider = FakeAccessTokenProvider()
        val fakeTpm = FakeTpmProvider(false)

        val result = AslHandshakeState.create(
            httpClient, request, tokenProvider, fakeTpm, tlsValidation = true,
            AslDependencies(AslStorageImpl(InMemoryStorage(), ResourceScope("", emptyList())), revStorage, clock = clock),
        )

        assertEquals(true, result.tlsValidation)
    }

    @Test
    fun create_initializesState_tlsValidationFalse() {
        val httpClient = ZetaHttpClient(HttpClient())
        val request = buildRequest()

        val result = AslHandshakeState.create(
            httpClient, request, FakeAccessTokenProvider(), FakeTpmProvider(false), tlsValidation = false,
            AslDependencies(AslStorageImpl(InMemoryStorage(), ResourceScope("", emptyList())), revStorage, clock = clock),
        )

        assertEquals(false, result.tlsValidation)
    }

    @Test
    fun aslUrl_usesEmptyPath_cidEmptyString() {
        val url = URLBuilder().apply {
            protocol = URLProtocol.HTTPS
            host = "api.example.com"
            port = 443
        }

        val result = aslUrl(url, cid = "")

        assertEquals("https://api.example.com", result)
    }

    @Test
    fun aslUrl_usesCidAsPath_cidWithoutLeadingSlash() {
        val url = URLBuilder().apply {
            protocol = URLProtocol.HTTPS
            host = "api.example.com"
            port = 443
        }

        val result = aslUrl(url, cid = "ASL/custom-cid-123")

        assertEquals("https://api.example.com/ASL/custom-cid-123", result)
    }

    @Test
    fun applyDpopFor_passesMethod_getRequest() = runBlocking {
        val tokenProvider = FakeAccessTokenProvider()
        val request = buildRequest(authHeader = "${HttpAuthHeaders.Dpop} token")
        val state = buildState(request = request).copy(accessTokenProvider = tokenProvider)

        state.applyDpopFor("GET", "https://example.com/resource")

        assertEquals("GET", tokenProvider.createDpopCalls.first().method)
    }

    @Test
    fun applyDpopFor_passesMethod_putRequest() = runBlocking {
        val tokenProvider = FakeAccessTokenProvider()
        val request = buildRequest(authHeader = "${HttpAuthHeaders.Dpop} token")
        val state = buildState(request = request).copy(accessTokenProvider = tokenProvider)

        state.applyDpopFor("PUT", "https://example.com/resource")

        assertEquals("PUT", tokenProvider.createDpopCalls.first().method)
    }

    @Test
    fun applyDpopFor_passesMethod_deleteRequest() = runBlocking {
        val tokenProvider = FakeAccessTokenProvider()
        val request = buildRequest(authHeader = "${HttpAuthHeaders.Dpop} token")
        val state = buildState(request = request).copy(accessTokenProvider = tokenProvider)

        state.applyDpopFor("DELETE", "https://example.com/resource")

        assertEquals("DELETE", tokenProvider.createDpopCalls.first().method)
    }

    @Test
    fun applyDpopFor_returnsDpopToken() = runBlocking {
        val tokenProvider = FakeAccessTokenProvider()
        val request = buildRequest(authHeader = "${HttpAuthHeaders.Dpop} token")
        val state = buildState(request = request).copy(accessTokenProvider = tokenProvider)

        val result = state.applyDpopFor("POST", "https://example.com")

        assertEquals("dpop_token", result)
    }

    @Test
    fun aslUrl_usesDefaultHttpPort_port80() {
        val url = URLBuilder().apply {
            protocol = URLProtocol.HTTP
            host = "api.example.com"
            port = 80
        }

        val result = aslUrl(url, cid = null)

        assertEquals("http://api.example.com/ASL", result)
    }

    @Test
    fun performMessage1AndReceiveMessage2_setsMessage1AndResult() = runTest {
        val state = buildState(
            request = buildRequest("DPoP some-token"),
            mockResponseHeaders = mapOf(
                HttpHeaders.ContentType to "application/cbor",
                "zeta-asl-cid" to "/valid-cid-123",
            ),
        )

        val result = state.performMessage1AndReceiveMessage2()

        assertNotNull(result.message1)
        assertNotNull(result.message1Result)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun sendMessage3AndReceiveMessage4_setsMessage4() = runTest {
        val validM4 = cbor.encodeToByteArray(Message4.serializer(), Message4("M4", ByteArray(28)))
        val state = buildState(
            message1Result = Message1Result("cid", ByteArray(1), ByteArray(1)),
            m3Encoded = ByteArray(1) { 1 },
            httpResponse = validM4,
            request = buildRequest("DPoP some-token"),
        )

        val result = state.sendMessage3AndReceiveMessage4()

        assertNotNull(result.message4)
        assertEquals("M4", result.message4.type)
    }

    private val aesGcm = AesGcmCipherImpl()
    private val fixedTranscriptHash = "transcript".toByteArray()
    private val validCiphertext = aesGcm.encrypt(ByteArray(32), fixedTranscriptHash)

    @Test
    fun validateMessage4AndEstablishSession_returnsSession_testingEnv() {
        val state = buildState(
            m3Result = buildM3Result(),
            message4 = Message4("M4", validCiphertext),
            transcriptHash = fixedTranscriptHash,
            message1Result = Message1Result("cid", ByteArray(1), ByteArray(1)),
        )
        val result = state.validateMessage4AndEstablishSession(false)
        assertNotNull(result)
        assertEquals(Environment.Testing, result.pu)
    }

    @Test
    fun validateMessage4AndEstablishSession_setsProductionEnv() {
        val state = buildState(
            m3Result = buildM3Result(),
            message4 = Message4("M4", validCiphertext),
            transcriptHash = fixedTranscriptHash,
            message1Result = Message1Result("cid", ByteArray(1), ByteArray(1)),
        )
        val result = state.validateMessage4AndEstablishSession(true)
        assertEquals(Environment.Production, result.pu)
    }

    private fun buildM3Result() = Message3Result(
        k2 = K2Keys(
            keyId = ByteArray(32),
            clientToServerAppDataKey = ByteArray(32),
            serverToClientAppDataKey = ByteArray(32),
            clientToServerConfirmationKey = ByteArray(32),
            serverToClientConfirmationKey = ByteArray(32),
            outputKeyingMaterial160 = ByteArray(32),
        ),
        m3Encoded = ByteArray(0),
        expectedTranscriptHash = ByteArray(0),
    )

    class FakeTpmProvider(private val hardwareBacked: Boolean) : TpmProvider {
        override suspend fun isHardwareBacked(): Boolean = hardwareBacked
        override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut {
            error("not in scope of the test")
        }

        override suspend fun generateDpopKey(): PublicKeyOut {
            return PublicKeyOut(byteArrayOf(), Jwk("", "", "", "", "", "", ""))
        }

        override suspend fun signWithClientKey(input: ByteArray): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray {
            return byteArrayOf()
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
    private class FakeAccessTokenProvider : AccessTokenProvider {
        val hashCalls = mutableListOf<String>()
        val createDpopCalls = mutableListOf<CreateDpopCall>()
        override suspend fun getValidToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, dpopKey: String): String = "valid_token"

        override suspend fun createDpopToken(dpopKey: Jwk, method: String, url: String, nonceBytes: ByteArray?, accessTokenHash: String?): String {
            createDpopCalls.add(CreateDpopCall(method, url, null, accessTokenHash))
            return "dpop_token"
        }

        override suspend fun hash(token: String): String {
            hashCalls.add(token)
            return "hashed_$token"
        }
        data class CreateDpopCall(
            val method: String,
            val url: String,
            val nonce: String?,
            val ath: String?,
        )
    }

    private fun buildRequest(authHeader: String? = null): HttpRequestBuilder {
        return HttpRequestBuilder().apply {
            if (authHeader != null) {
                headers[HttpHeaders.Authorization] = authHeader
            }
        }
    }

    private fun buildState(
        request: HttpRequestBuilder = buildRequest(),
        message1: Message1Bundle? = null,
        message1Result: Message1Result? = null,
        m3Result: Message3Result? = null,
        m3Encoded: ByteArray? = null,
        transcriptHash: ByteArray? = null,
        message4: Message4? = null,
        httpResponse: ByteArray = ByteArray(0),
        mockResponseHeaders: Map<String, String> = emptyMap(),
    ): AslHandshakeState {
        val engine = MockEngine {
            respond(
                content = httpResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    *mockResponseHeaders.entries
                        .map { it.key to listOf(it.value) }
                        .toTypedArray(),
                ),
            )
        }
        return AslHandshakeState(
            request = request,
            httpClient = ZetaHttpClient(HttpClient(engine)),
            mlKem = ML768Kem(),
            ecdhKem = EcdhP256Kem(),
            message1 = message1,
            message1Result = message1Result,
            m3Result = m3Result,
            m3Encoded = m3Encoded,
            transcriptHash = transcriptHash,
            message4 = message4,
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = FakeTpmProvider(false),
            storage = AslStorageImpl(InMemoryStorage(), ResourceScope("", emptyList())),
            revocationChecker = revStorage,
            clock = clock,
        )
    }
}
