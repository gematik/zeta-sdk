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
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AslApiImplTest {
    val fakeTarget = "https://api.example.com/resource/data"
    val fakeToken = "dpop token"
    val fakeSession = "/session/abc123"
    val requiredOid = "1.2.276.0.76.4.261"

    private fun testrevocationChecker(): RevocationChecker =
        RevocationChecker(RevocationStorage(InMemoryStorage(), ResourceScope("", listOf())), HttpClient {})

    @Test
    fun decrypt_throwsException_sessionIsNull() = runTest {
        // Arrange
        val storage = FakeAslStorage(session = null)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val encrypted = byteArrayOf(0x01, 0x02)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            sut.decrypt(encrypted)
        }
    }

    @Test
    fun decrypt_throwsException_extendedTooShort() = runTest {
        // Arrange
        val session = buildSession()
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val tooShortPayload = byteArrayOf(0x00)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            sut.decrypt(tooShortPayload)
        }
    }

    @Test
    fun encrypt_setsMethodToPost_sessionExists() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request)

        // Assert
        assertEquals(HttpMethod.Post, result.method)
    }

    @Test
    fun encrypt_setsUrlPathToCid_sessionExists() = runTest {
        // Arrange
        val cid = "/session/xyz789"
        val session = buildSession(cid = cid)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request)

        // Assert
        assertEquals(cid, result.url.encodedPath)
    }

    @Test
    fun encrypt_setsDpopHeader_sessionExists() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        FakeAccessTokenProvider()
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request, true)

        // Assert
        assertEquals("fake-dpop-token", result.headers["dpop"])
    }

    @Test
    fun encrypt_setsOctetStreamContentType_sessionExists() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request)

        // Assert
        assertEquals("application/octet-stream", result.headers[HttpHeaders.ContentType])
    }

    @Test
    fun encrypt_setsOctetStreamAccept_sessionExists() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request)

        // Assert
        assertEquals("application/octet-stream", result.headers[HttpHeaders.Accept])
    }

    @Test
    fun encrypt_callsHashWithToken_bearerHeaderPresent() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val tokenProvider = FakeAccessTokenProvider()
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = tokenProvider,
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        sut.encrypt(request, true)

        // Assert
        assertEquals("token", tokenProvider.lastHashInput)
    }

    @Test
    fun encrypt_passesNullHash_bearerHeaderAbsent() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val tokenProvider = FakeAccessTokenProvider()
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
        }

        // Act
        sut.encrypt(request)

        // Assert
        assertNull(tokenProvider.lastDpopHash)
    }

    @Test
    fun encrypt_passesDpopMethodPost_sessionExists() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val tokenProvider = FakeAccessTokenProvider()
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = tokenProvider,
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        sut.encrypt(request, true)

        // Assert
        assertEquals("POST", tokenProvider.lastDpopMethod)
    }

    @Test
    fun encrypt_callsSaveSession_sessionExists() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        sut.encrypt(request)

        // Assert
        assertTrue(storage.sessionWasSaved)
        assertNotNull(storage.savedSession)
    }

    @Test
    fun encrypt_omitsTracingHeader_prodEnvironment() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession, prod = true)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request)

        // Assert
        assertNull(result.headers[TRACING_HEADER])
    }

    @Test
    fun encrypt_includesTracingHeader_nonProdEnvironment() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession, prod = false)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = false,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request)

        // Assert
        assertNotNull(result.headers["ZETA-ASL-nonPU-Tracing"])
    }

    @Test
    fun encrypt_throwsException_cidIsNull() = runTest {
        // Arrange
        val session = buildSession(cid = null)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            sut.encrypt(request)
        }
    }

    @Test
    fun copyAuthHeadersFrom_copiesAuthHeader_headerPresent() {
        // Arrange
        val source = HttpRequestBuilder().apply {
            header(HttpHeaders.Authorization, "DPoP source-token")
        }
        val target = HttpRequestBuilder()

        // Act
        target.copyAuthHeadersFrom(source)

        // Assert
        assertEquals("DPoP source-token", target.headers[HttpHeaders.Authorization])
    }

    @Test
    fun copyAuthHeadersFrom_doesNotOverwrite_headerAbsent() {
        // Arrange
        val source = HttpRequestBuilder()
        val target = HttpRequestBuilder().apply {
            header(HttpHeaders.Authorization, "DPoP existing-token")
        }

        // Act
        target.copyAuthHeadersFrom(source)

        // Assert
        assertEquals("DPoP existing-token", target.headers[HttpHeaders.Authorization])
    }

    @Test
    fun encrypt_replacesCallerAccept_sessionExists() = runTest {
        // Arrange
        val storage = FakeAslStorage(session = buildSession(cid = fakeSession))
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
            header(HttpHeaders.Accept, "application/fhir+json")
        }

        // Act
        val result = sut.encrypt(request)

        // Assert
        assertEquals(listOf("application/octet-stream"), result.headers.getAll(HttpHeaders.Accept))
    }

    @Test
    fun encrypt_doesNotAccumulateHeaders_calledTwice() = runTest {
        // Arrange
        val storage = FakeAslStorage(session = buildSession(cid = "/ASL/aaa"))
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act — first send, server answers 500, session is recreated, retry
        sut.encrypt(request)
        storage.session = buildSession(cid = "/ASL/bbb")
        val result = sut.encrypt(request)

        // Assert
        assertEquals(listOf("application/octet-stream"), result.headers.getAll(HttpHeaders.Accept))
        assertEquals(listOf("application/octet-stream"), result.headers.getAll(HttpHeaders.ContentType))
    }

    @Test
    fun encrypt_keepsSingleTracingHeaderOfCurrentSession_calledTwice() = runTest {
        // Arrange
        val sessionB = buildSession(
            cid = "/ASL/bbb",
            prod = false,
            c2sAppDataKey = ByteArray(32) { 0x04 },
            s2cAppDataKey = ByteArray(32) { 0x05 },
        )
        val storage = FakeAslStorage(session = buildSession(cid = "/ASL/aaa", prod = false))
        val sut = AslApiImpl(
            aslProdEnvironment = false,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        sut.encrypt(request)
        storage.session = sessionB
        val result = sut.encrypt(request)

        // Assert
        val expected = "${Base64.encode(sessionB.c2sAppDataKey)} ${Base64.encode(sessionB.s2cAppDataKey)}"
        assertEquals(listOf(expected), result.headers.getAll(TRACING_HEADER))
    }

    @Test
    fun encrypt_doesNotGrowBody_calledTwice() = runTest {
        // Arrange
        val storage = FakeAslStorage(session = buildSession(cid = "/ASL/aaa"))
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom("https://api.example.com/vsdservice/v1/vsdmbundle?profileVersion=1.1") }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
            header(HttpHeaders.Accept, "application/fhir+json")
        }

        // Act — first send, server answers 500, session is recreated, retry
        sut.encrypt(request)
        val sizeAfterFirst = (request.body as ByteArray).size
        storage.session = buildSession(cid = "/ASL/bbb")
        val result = sut.encrypt(request)

        // Assert — same plaintext re-encrypted, not the previous ciphertext wrapped again
        assertEquals(sizeAfterFirst, (result.body as ByteArray).size)
        assertEquals("/ASL/bbb", result.url.encodedPath)
    }

    @Test
    fun encrypt_keepsOriginalInnerRequest_calledTwice() = runTest {
        // Arrange
        val storage = FakeAslStorage(session = buildSession(cid = "/ASL/aaa"))
        val sut = AslApiImpl(
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            requiredRoleOid = requiredOid, revocationChecker = testrevocationChecker(),
        )
        val target = "https://api.example.com/vsdservice/v1/vsdmbundle?profileVersion=1.1"
        val request = HttpRequestBuilder().apply {
            url { takeFrom(target) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
            header(HttpHeaders.Accept, "application/fhir+json")
        }
        // an identical builder that is never passed to encrypt, used as the expected value
        val pristineCopy = HttpRequestBuilder().apply {
            url { takeFrom(target) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
            header(HttpHeaders.Accept, "application/fhir+json")
        }
        val expected = InnerHttpCodecImpl().encodeRequest(pristineCopy)

        // Act
        sut.encrypt(request)
        storage.session = buildSession(cid = "/ASL/bbb")
        sut.encrypt(request)

        // Assert — the cached inner request is still the caller's original GET,
        // not a POST to the previous CID carrying the previous ciphertext
        assertContentEquals(expected, request.attributes[AslInnerRequestKey])
        val decoded = expected.decodeToString()
        assertTrue(decoded.startsWith("GET /vsdservice/v1/vsdmbundle?profileVersion=1.1"))
        assertTrue(decoded.contains("application/fhir+json"))
    }

    private class FakeAslStorage(var session: EstablishedSession? = null) : AslStorage {
        var sessionWasSaved = false
        var savedSession: EstablishedSession? = null

        override suspend fun getCurrentSession(): EstablishedSession? = session
        override suspend fun getCachedCertData(certificateHashHex: String, certificateDescriptionVersion: Int): CertData? = null
        override suspend fun saveCachedCertData(certificateHashHex: String, certificateDescriptionVersion: Int, certData: CertData) {
            // no-opts
        }

        override suspend fun saveSession(session: EstablishedSession) {
            sessionWasSaved = true
            savedSession = session
        }
        override suspend fun clear() {}
    }

    class FakeAccessTokenProvider : AccessTokenProvider {
        var lastHashInput: String? = null
        var lastDpopMethod: String? = null
        var lastDpopUrl: String? = null
        var lastDpopHash: String? = null

        override suspend fun getValidToken(
            tokenEndpoint: String,
            nonceEndpoint: String,
            params: AccessTokenParams,
            dpopKey: String,
        ): String = "fake-access-token"

        override suspend fun createDpopToken(
            dpopKey: Jwk,
            method: String,
            url: String,
            nonceBytes: ByteArray?,
            accessTokenHash: String?,
        ): String {
            lastDpopMethod = method
            lastDpopUrl = url
            lastDpopHash = accessTokenHash
            return "fake-dpop-token"
        }

        override suspend fun hash(token: String): String {
            lastHashInput = token
            return "fake-hash"
        }
    }

    fun buildSession(
        cid: String? = fakeSession,
        prod: Boolean = true,
        c2sAppDataKey: ByteArray = ByteArray(32) { 0x02 },
        s2cAppDataKey: ByteArray = ByteArray(32) { 0x03 },
    ): EstablishedSession =
        EstablishedSession(
            keyId = ByteArray(32) { 0x01 },
            c2sAppDataKey = c2sAppDataKey,
            s2cAppDataKey = s2cAppDataKey,
            cid = cid,
            pu = if (prod) Environment.Production else Environment.Testing,
        )
}
