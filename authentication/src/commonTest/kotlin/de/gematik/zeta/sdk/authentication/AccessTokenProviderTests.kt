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

package de.gematik.zeta.sdk.authentication

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.attestation.AttestationApi
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.model.AccessTokenResponse
import de.gematik.zeta.sdk.authentication.oidc.BindEmailRequest
import de.gematik.zeta.sdk.authentication.oidc.BindEmailResponse
import de.gematik.zeta.sdk.authentication.oidc.OidcAccessTokenResponse
import de.gematik.zeta.sdk.authentication.oidc.OidcTokenIssuance
import de.gematik.zeta.sdk.authentication.oidc.OtpVerifyRequest
import de.gematik.zeta.sdk.authentication.oidc.ParResponse
import de.gematik.zeta.sdk.authentication.oidc.VerifyOtpResponse
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.client.call.HttpClientCall
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AccessTokenProviderImplTest {
    private val requiredOid = "1.2.276.0.76.4.261"

    @Test
    fun getValidToken_returnsCachedToken_whenValidAndNotExpired() = runTest {
        // Arrange
        val fakeStorage = FakeAuthStorage(accessToken = "cached_token", expiration = "2000")
        val (sut, _) = buildSut(clock = { 1000L }, fakeAuthStorage = fakeStorage)

        // Act
        val result = sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals("cached_token", result)
    }

    @Test
    fun getValidToken_returnsCachedToken_aboveSafetyMargin() = runTest {
        // Arrange - expires at 1015, clock 1000 → 15s remaining > 10s margin
        val fakeStorage = FakeAuthStorage(accessToken = "valid_token", expiration = "1015")
        val (sut, _) = buildSut(clock = { 1000L }, fakeAuthStorage = fakeStorage)

        // Act
        val result = sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals("valid_token", result)
    }

    @Test
    fun getValidToken_fetchesNewToken_whenWithinSafetyMargin() = runTest {
        // Arrange - expires at 1005, clock 1000 → 5s remaining < 10s margin
        val fakeStorage = FakeAuthStorage(accessToken = "expiring_token", expiration = "1005")
        val fakeApi = FakeAuthApi(tokenResponse = buildTokenResponse("new_token"))
        val (sut, storage) = buildSut(clock = { 1000L }, fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act
        val result = sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals("new_token", result)
        assertEquals(1, storage.savedTokens.size)
    }

    @Test
    fun getValidToken_fetchesNewToken_whenExpirationIsEmpty() = runTest {
        // Arrange
        val fakeStorage = FakeAuthStorage(accessToken = "cached_token", expiration = "")
        val fakeApi = FakeAuthApi(tokenResponse = buildTokenResponse("fresh_token"))
        val (sut, _) = buildSut(fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act
        val result = sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals("fresh_token", result)
    }

    @Test
    fun getValidToken_fetchesNewToken_whenNoCachedToken() = runTest {
        // Arrange
        val fakeStorage = FakeAuthStorage()
        val fakeApi = FakeAuthApi(tokenResponse = buildTokenResponse("fresh_token"))
        val (sut, storage) = buildSut(fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act
        val result = sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals("fresh_token", result)
        assertEquals(1, storage.savedTokens.size)
    }

    @Test
    fun getValidToken_usesRefreshToken_whenAccessTokenExpired() = runTest {
        // Arrange
        val fakeStorage = FakeAuthStorage(
            accessToken = "old_token",
            refreshToken = "valid_refresh_token",
            expiration = "500",
        )
        val fakeApi = FakeAuthApi(tokenResponse = buildTokenResponse("refreshed_token"))
        val (sut, storage) = buildSut(clock = { 1000L }, fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act
        val result = sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals("refreshed_token", result)
        assertEquals(1, storage.savedTokens.size)
    }

    @Test
    fun getValidToken_fallsBackToNewToken_whenRefreshFails() = runTest {
        val notRequired = "Not required for the test"
        // Arrange
        val fakeStorage = FakeAuthStorage(
            accessToken = "old_token",
            refreshToken = "expired_refresh_token",
            expiration = "500",
        )
        var callCount = 0
        val fakeApi = object : AuthenticationApi {
            override suspend fun fetchNonce(nonceEndpoint: String): ByteArray = ByteArray(0)
            override suspend fun requestAccessToken(
                fromEndpoint: String,
                accessTokenRequest: AccessTokenRequest,
                dpopToken: String,
            ): AccessTokenResponse {
                callCount++
                if (callCount == 1) throw AuthenticationException(fakeHttpResponse(), "refresh failed")
                return buildTokenResponse("fallback_token")
            }

            override suspend fun requestOidcToken(fromEndpoint: String, accessTokenRequest: OidcTokenIssuance.OidcTokenRequest, dpopToken: String): OidcAccessTokenResponse {
                error(notRequired)
            }

            override suspend fun postBindEmail(endpoint: String, accessToken: String, dpop: String, body: BindEmailRequest): BindEmailResponse {
                error(notRequired)
            }

            override suspend fun postResendOtp(endpoint: String, accessToken: String, dpop: String): BindEmailResponse {
                error(notRequired)
            }

            override suspend fun postVerifyOtp(endpoint: String, accessToken: String, dpop: String, body: OtpVerifyRequest): VerifyOtpResponse {
                error(notRequired)
            }

            override suspend fun requestPar(endpoint: String, parameters: Parameters): ParResponse {
                error(notRequired)
            }
        }
        val (sut, storage) = buildSut(clock = { 1000L }, fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act
        val result = sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals("fallback_token", result)
        assertEquals(1, storage.savedTokens.size)
    }

    @Test
    fun getValidToken_throwsAuthenticationException_whenBothRefreshAndNewTokenFail() = runTest {
        // Arrange
        val fakeStorage = FakeAuthStorage(
            accessToken = "old_token",
            refreshToken = "bad_refresh",
            expiration = "500",
        )
        val fakeApi = FakeAuthApi(throwOnToken = AuthenticationException(fakeHttpResponse(), "auth failed"))
        val (sut, _) = buildSut(clock = { 1000L }, fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act & Assert
        assertFailsWith<AuthenticationException> {
            sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")
        }
    }

    @Test
    fun getValidToken_throwsAuthenticationException_whenNoRefreshAndNewTokenFails() = runTest {
        // Arrange
        val fakeStorage = FakeAuthStorage()
        val fakeApi = FakeAuthApi(throwOnToken = AuthenticationException(fakeHttpResponse(), "auth failed"))
        val (sut, _) = buildSut(fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act & Assert
        assertFailsWith<AuthenticationException> {
            sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")
        }
    }

    @Test
    fun getValidToken_savesTokensWithCorrectExpiration_afterSuccessfulFetch() = runTest {
        // Arrange
        val fakeStorage = FakeAuthStorage()
        val fakeApi = FakeAuthApi(tokenResponse = buildTokenResponse("new_token", expiresIn = 3600))
        val (sut, storage) = buildSut(clock = { 1000L }, fakeAuthStorage = fakeStorage, fakeAuthApi = fakeApi)

        // Act
        sut.getValidToken("https://token.endpoint", "https://nonce.endpoint", defaultParams, "")

        // Assert
        assertEquals(1, storage.savedTokens.size)
        assertEquals("new_token", storage.savedTokens[0].first)
        assertEquals(1000L + 3600, storage.savedTokens[0].third)
    }

    @Test
    fun hash_returnsBase64UrlEncoded_withoutPaddingOrStandardChars() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result = sut.hash("some_access_token")

        // Assert
        assertTrue(result.isNotBlank())
        assertTrue(!result.contains('+'))
        assertTrue(!result.contains('/'))
        assertTrue(!result.contains('='))
    }

    @Test
    fun hash_returnsSameValue_forSameInput() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result1 = sut.hash("test_token")
        val result2 = sut.hash("test_token")

        // Assert
        assertEquals(result1, result2)
    }

    @Test
    fun hash_returnsDifferentValue_forDifferentInput() = runTest {
        // Arrange
        val (sut, _) = buildSut()

        // Act
        val result1 = sut.hash("token_a")
        val result2 = sut.hash("token_b")

        // Assert
        assertNotEquals(result1, result2)
    }

    @Test
    fun values_shallContainExactlySmbAndOidc() {
        val values = AuthMode.entries

        assertEquals(listOf(AuthMode.SMB, AuthMode.OIDC), values)
    }

    @Test
    fun valueOf_shallResolveSmb() {
        assertEquals(AuthMode.SMB, AuthMode.valueOf("SMB"))
    }

    @Test
    fun valueOf_shallResolveOidc() {
        assertEquals(AuthMode.OIDC, AuthMode.valueOf("OIDC"))
    }

    @Test
    fun valueOf_shallThrow_forUnknownName() {
        assertFailsWith<IllegalArgumentException> {
            AuthMode.valueOf("SMC_B")
        }
    }

    @Test
    fun valueOf_shallBeCaseSensitive() {
        assertFailsWith<IllegalArgumentException> {
            AuthMode.valueOf("smb")
        }
    }

    @Test
    fun ordinal_shallMatchDeclarationOrder() {
        assertEquals(0, AuthMode.SMB.ordinal)
        assertEquals(1, AuthMode.OIDC.ordinal)
    }

    @Test
    fun name_shallMatchEnumConstantName() {
        assertEquals("SMB", AuthMode.SMB.name)
        assertEquals("OIDC", AuthMode.OIDC.name)
    }

    @Test
    fun whenExpression_shallBeExhaustiveOverBothValues() {
        fun describe(mode: AuthMode): String = when (mode) {
            AuthMode.SMB -> "smb"
            AuthMode.OIDC -> "oidc"
        }

        assertEquals("smb", describe(AuthMode.SMB))
        assertEquals("oidc", describe(AuthMode.OIDC))
    }

    class FakeTpmProvider : TpmProvider {
        override suspend fun isHardwareBacked(): Boolean = false
        override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut = PublicKeyOut(
            encoded = ByteArray(32) { 0x01 },
            jwk = Jwk(
                kid = "fake-kid",
                kty = "EC",
                alg = "ES256",
                use = "sig",
                crv = "P-256",
                x = "fake-x",
                y = "fake-y",
            ),
        )
        override suspend fun generateDpopKey(): PublicKeyOut = PublicKeyOut(
            encoded = ByteArray(32) { 0x01 },
            jwk = Jwk(
                kid = "fake-kid",
                kty = "EC",
                alg = "ES256",
                use = "sig",
                crv = "P-256",
                x = "fake-x",
                y = "fake-y",
            ),
        )
        override suspend fun signWithClientKey(input: ByteArray): ByteArray = ByteArray(64) { 0x02 }
        override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray = ByteArray(64) { 0x03 }
        override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray = ByteArray(0)
        override suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray = ByteArray(0)
        override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray = ByteArray(0)
        override suspend fun signWithSmbKeyFromBytes(input: ByteArray, keystoreBytes: ByteArray, alias: String, password: String): ByteArray {
            error("not in scope of the test")
        }

        override suspend fun randomUuid(): Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
        override suspend fun getRegistrationNumber(certificate: ByteArray): String = "fake-reg-number"
        override suspend fun forget(resource: String?) {}
    }

    private class FakeAttestationApi : AttestationApi {
        override suspend fun createClientAssertion(
            productId: String,
            productVersion: String,
            nonce: ByteArray,
            clientId: String,
            exp: Long,
            aud: String,
            platformProductId: PlatformProductId,
        ): String = "fake_client_assertion"
    }

    private fun buildTokenResponse(
        accessToken: String = "access_token",
        refreshToken: String = "refresh_token",
        expiresIn: Int = 3600,
    ) = AccessTokenResponse(
        accessToken = accessToken,
        expiresIn = expiresIn,
        refreshExpires = 7200,
        tokenType = "DPoP",
        notBeforePolicy = "0",
        sessionState = "session",
        scope = "scope1",
        issuedTokenType = "urn:ietf:params:oauth:token-type:access_token",
        refreshToken = refreshToken,
    )

    private class FakeAuthApi(
        private val nonce: ByteArray = ByteArray(0),
        private val tokenResponse: AccessTokenResponse = AccessTokenResponse(
            accessToken = "access_token",
            expiresIn = 3600,
            refreshExpires = 7200,
            tokenType = "DPoP",
            notBeforePolicy = "0",
            sessionState = "session",
            scope = "scope1",
            issuedTokenType = "urn:ietf:params:oauth:token-type:access_token",
            refreshToken = "refresh_token",
        ),
        private val throwOnToken: Exception? = null,
    ) : AuthenticationApi {
        val notRequired = "Not yet implemented"
        var requestAccessTokenCallCount = 0

        override suspend fun fetchNonce(nonceEndpoint: String): ByteArray = nonce
        override suspend fun requestAccessToken(
            fromEndpoint: String,
            accessTokenRequest: AccessTokenRequest,
            dpopToken: String,
        ): AccessTokenResponse {
            requestAccessTokenCallCount++
            throwOnToken?.let { throw it }
            return tokenResponse
        }

        override suspend fun requestOidcToken(fromEndpoint: String, accessTokenRequest: OidcTokenIssuance.OidcTokenRequest, dpopToken: String): OidcAccessTokenResponse {
            error(notRequired)
        }

        override suspend fun postBindEmail(endpoint: String, accessToken: String, dpop: String, body: BindEmailRequest): BindEmailResponse {
            error(notRequired)
        }

        override suspend fun postResendOtp(endpoint: String, accessToken: String, dpop: String): BindEmailResponse {
            error(notRequired)
        }

        override suspend fun postVerifyOtp(endpoint: String, accessToken: String, dpop: String, body: OtpVerifyRequest): VerifyOtpResponse {
            error(notRequired)
        }

        override suspend fun requestPar(endpoint: String, parameters: Parameters): ParResponse {
            error(notRequired)
        }
    }

    private class FakeAuthStorage(
        private var accessToken: String? = null,
        private var refreshToken: String? = null,
        private var expiration: String? = null,
    ) : AuthenticationStorage {
        val savedTokens = mutableListOf<Triple<String, String, Long>>()

        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun getRefreshToken(): String? = refreshToken
        override suspend fun getTokenExpiration(): String? = expiration
        override suspend fun saveAccessTokens(
            accessToken: String,
            refreshToken: String,
            expiresAt: Long,
        ) {
            savedTokens.add(Triple(accessToken, refreshToken, expiresAt))
            this.accessToken = accessToken
        }
        override suspend fun clearAccessToken() {
            accessToken = null
            expiration = null
        }
        override suspend fun clear() {}
    }

    private class FakeSubjectTokenProvider : SubjectTokenProvider {
        override suspend fun createSubjectToken(
            clientId: String,
            dpopKey: String,
            nonceBytes: ByteArray,
            audience: String,
            now: Long,
            expiration: Long,
            tpmProvider: TpmProvider,
        ): String = "fake_subject_token"
    }

    private fun fakeHttpResponse(): HttpResponse = object : HttpResponse() {
        override val call: HttpClientCall
            get() = error("not in scope of the test")
        override val status: HttpStatusCode
            get() = error("not in scope of the test")
        override val version: HttpProtocolVersion
            get() = error("not in scope of the test")
        override val requestTime: GMTDate
            get() = error("not in scope of the test")
        override val responseTime: GMTDate
            get() = error("not in scope of the test")

        @InternalAPI
        override val rawContent: ByteReadChannel
            get() = error("not in scope of the test")
        override val headers: Headers
            get() = error("not in scope of the test")
        override val coroutineContext: CoroutineContext
            get() = error("not in scope of the test")
    }

    private fun buildAuthConfig() = AuthConfig(
        scopes = listOf("scope1"),
        exp = 300L,
        subjectTokenProvider = FakeSubjectTokenProvider(),
        attestation = AttestationConfig.software(),
        requiredRoleOid = requiredOid,
    )

    private fun buildSut(
        resource: String = "https://resource.example.com",
        clock: () -> Long = { 1000L },
        fakeAuthApi: AuthenticationApi = FakeAuthApi(),
        fakeAuthStorage: FakeAuthStorage = FakeAuthStorage(),
        fakeTpmProvider: TpmProvider = FakeTpmProvider(),
        fakeAttestationApi: FakeAttestationApi = FakeAttestationApi(),
    ): Pair<AccessTokenProviderImpl, FakeAuthStorage> {
        val sut = object : AccessTokenProviderImpl(
            resource = resource,
            authConfig = buildAuthConfig(),
            authApi = fakeAuthApi,
            authStorage = fakeAuthStorage,
            clock = clock,
            tpmProvider = fakeTpmProvider,
        ) {
            override val attestationApi: AttestationApi = fakeAttestationApi
        }
        return sut to fakeAuthStorage
    }

    private val defaultParams = AccessTokenParams(
        clientId = "client_id",
        productId = "product_id",
        productVersion = "1.0.0",
        expiration = 300L,
        scopes = listOf("scope1", "scope2"),
        audience = "https://auth.example.com",
        platformProductId = PlatformProductId.LinuxProductId("linux", "linux", "linux", "linux"),
    )
}
