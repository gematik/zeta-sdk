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
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.oidc.TokenIssuanceRequest
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.tpm.TpmProvider
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SubjectTokenIssuanceTests {
    @Test
    fun issue_storesZetaAttestationToken_whenPresent() = runTest {
        // Arrange
        val engine = tokenEngine(
            """{"access_token":"at","expires_in":300,"refresh_expires_in":1800,"token_type":"DPoP","not-before-policy":"0","session_state":"session","scope":"openid","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","refresh_token":"rt","zeta_attestation_token":"zat"}""",
        )
        val storage = newAuthStorage()
        val state = AttestationStateStorage(InMemoryStorage())
        val sut = buildSut(engine, storage, state)

        // Act
        val accessToken = sut.issue(fakeRequest(), "dpop-key", FakeSubjectTokenProvider())

        // Assert
        assertEquals("at", accessToken)
        assertEquals("zat", state.getZetaAttestationToken())
        assertEquals("at", storage.getAccessToken())
    }

    @Test
    fun issue_keepsExistingToken_whenResponseHasNone() = runTest {
        // Arrange
        val engine = tokenEngine(
            """{"access_token":"at","expires_in":300,"refresh_expires_in":1800,"token_type":"DPoP","not-before-policy":"0","session_state":"session","scope":"openid","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","refresh_token":"rt"}""",
        )
        val storage = newAuthStorage()
        val state = AttestationStateStorage(InMemoryStorage())
        state.setZetaAttestationToken("old")
        val sut = buildSut(engine, storage, state)

        // Act
        sut.issue(fakeRequest(), "dpop-key", FakeSubjectTokenProvider())

        // Assert
        assertEquals("old", state.getZetaAttestationToken())
    }

    private fun tokenEngine(body: String): MockEngine = MockEngine {
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun newAuthStorage(): AuthenticationStorage =
        AuthenticationStorageImpl(InMemoryStorage(), resourceScope = ResourceScope("", emptyList()))

    private fun buildSut(
        engine: MockEngine,
        authStorage: AuthenticationStorage,
        attestationState: AttestationStateStorage?,
    ): SubjectTokenIssuance = SubjectTokenIssuance(
        authConfig = AuthConfig(
            scopes = listOf("openid"),
            exp = 300L,
            subjectTokenProvider = FakeSubjectTokenProvider(),
            attestation = AttestationConfig.software(),
            requiredRoleOid = "1.2.276.0.76.4.261",
        ),
        authApi = AuthenticationApiImpl(ZetaHttpClient(HttpClient(engine)), SystemZetaClock),
        authStorage = authStorage,
        tpmProvider = FakeTpmProvider(),
        clock = { 1000L },
        attestationState = attestationState,
    )

    private fun fakeRequest(): TokenIssuanceRequest = TokenIssuanceRequest(
        tokenEndpoint = "https://zeta-kind.local/token",
        nonce = ByteArray(0),
        dpopProvider = { _, _ -> "fake-dpop" },
        clientAssertionProvider = { "fake-client-assertion" },
        params = AccessTokenParams(
            clientId = "test-client-id",
            scopes = listOf("openid"),
            audience = "https://zeta-kind.local/pep/",
            productId = "",
            productVersion = "",
            expiration = 30,
            platformProductId = PlatformProductId.LinuxProductId("", "", "", ""),
        ),
    )

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

    private class FakeTpmProvider : TpmProvider {
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
}
