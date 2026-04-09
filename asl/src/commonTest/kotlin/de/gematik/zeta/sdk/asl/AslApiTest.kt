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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AslApiImplTest {
    val fakeTarget = "https://api.example.com/resource/data"
    val fakeToken = "DPoP token"
    val fakeResource = "test-resource"
    val fakeSession = "/session/abc123"

    @Test
    fun decrypt_throwsException_sessionIsNull() = runTest {
        // Arrange
        val storage = FakeAslStorage(session = null)
        val sut = AslApiImpl(
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        val result = sut.encrypt(request, true)

        // Assert
        assertEquals("fake-dpop-token", result.headers["DPoP"])
    }

    @Test
    fun encrypt_setsOctetStreamContentType_sessionExists() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = tokenProvider,
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = tokenProvider,
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
        )
        val request = HttpRequestBuilder().apply {
            url { takeFrom(fakeTarget) }
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, fakeToken)
        }

        // Act
        sut.encrypt(request)

        // Assert
        assertEquals(fakeResource, storage.savedFqdn)
        assertNotNull(storage.savedSession)
    }

    @Test
    fun encrypt_omitsTracingHeader_prodEnvironment() = runTest {
        // Arrange
        val session = buildSession(cid = fakeSession, prod = true)
        val storage = FakeAslStorage(session = session)
        val sut = AslApiImpl(
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = false,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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
            resource = fakeResource,
            aslProdEnvironment = true,
            aslStorage = storage,
            zetaHttpClient = ZetaHttpClient(HttpClient {}),
            accessTokenProvider = FakeAccessTokenProvider(),
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
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

    class FakeAslStorage(private val session: EstablishedSession? = null) : AslStorage {
        var savedFqdn: String? = null
        var savedSession: EstablishedSession? = null

        override suspend fun getCurrentSession(fqdn: String): EstablishedSession? = session
        override suspend fun saveSession(fqdn: String, session: EstablishedSession) {
            savedFqdn = fqdn
            savedSession = session
        }
        override suspend fun clear(fqdn: String) {}
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

    fun buildSession(cid: String? = fakeSession, prod: Boolean = true): EstablishedSession =
        EstablishedSession(
            keyId = ByteArray(32) { 0x01 },
            c2sAppDataKey = ByteArray(32) { 0x02 },
            s2cAppDataKey = ByteArray(32) { 0x03 },
            cid = cid,
            pu = if (prod) Environment.Production else Environment.Testing,
        )
}
