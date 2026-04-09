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

package de.gematik.zeta.sdk.authentication.smcb

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.authentication.smcb.model.ExternalAuthenticateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.ReadCardCertificateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.SignatureObject
import de.gematik.zeta.sdk.authentication.smcb.model.Status
import de.gematik.zeta.sdk.authentication.smcb.model.X509Data
import de.gematik.zeta.sdk.authentication.smcb.model.X509DataInfo
import de.gematik.zeta.sdk.authentication.smcb.model.X509DataInfoList
import de.gematik.zeta.sdk.authentication.smcb.model.X509IssuerSerial
import de.gematik.zeta.sdk.authentication.toBase64
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import joseToDerEcdsa
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SmcbTokenProviderAdditionalTest {

    private val tpmProvider = mockk<TpmProvider>()
    private val connectorApi = mockk<ConnectorApi>()

    private val connectorConfig = SmcbTokenProvider.ConnectorConfig(
        baseUrl = "https://connector.test",
        mandantId = "mandant1",
        clientSystemId = "clientSys1",
        workspaceId = "workspace1",
        userId = "user1",
        cardHandle = "card1",
    )

    private val subjectTokenProvider = SmcbTokenProvider(
        connectorConfig = connectorConfig,
        connectorApi = connectorApi,
    )

    @Test
    fun `given subject token when created then token has three parts separated by dots`() =
        runTest {
            // given
            setupMocks()

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            val parts = token.split(".")
            assertEquals(3, parts.size)
        }

    @Test
    fun `given subject token when created then header contains correct typ and alg`() =
        runTest {
            // given
            setupMocks()

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            val headerJson = decodeJwtPart(token.split(".")[0])
            assertTrue(headerJson.contains("\"typ\":\"JWT\""))
            assertTrue(headerJson.contains("\"alg\":\"ES256\""))
        }

    @Test
    fun `given subject token when created then claims contain correct iss aud and sub`() =
        runTest {
            // given
            setupMocks()

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "myClient", "", "nonce".toByteArray(), "myAudience",
                1000L, 30L, tpmProvider,
            )

            // then
            val claimsJson = decodeJwtPart(token.split(".")[1])
            assertTrue(claimsJson.contains("\"iss\":\"myClient\""))
            assertTrue(claimsJson.contains("\"myAudience\""))
            assertTrue(claimsJson.contains("\"sub\":\"regNumber\""))
        }

    @Test
    fun `given subject token when created then exp equals now plus expiration`() =
        runTest {
            // given
            val now = 5000L
            val expiration = 120L
            setupMocks()

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "", "nonce".toByteArray(), "audience",
                now, expiration, tpmProvider,
            )

            // then
            val claimsJson = decodeJwtPart(token.split(".")[1])
            assertTrue(claimsJson.contains("\"exp\":${now + expiration}"))
            assertTrue(claimsJson.contains("\"iat\":$now"))
        }

    @Test
    fun `given connector readCertificate fails when createSubjectToken then exception propagates`() =
        runTest {
            // given
            coEvery {
                connectorApi.readCertificate(any(), any(), any(), any(), any())
            } throws ConnectorError("SOAP-ENV:Server", "read failed", "read failed")

            // when & then
            assertFailsWith<ConnectorError> {
                subjectTokenProvider.createSubjectToken(
                    "clientId", "", "nonce".toByteArray(), "audience",
                    1000L, 30L, tpmProvider,
                )
            }
        }

    @Test
    fun `given connector externalAuthenticate fails when createSubjectToken then exception propagates`() =
        runTest {
            // given
            val certificate = "certificate".toByteArray()
            coEvery {
                connectorApi.readCertificate("card1", "mandant1", "clientSys1", "workspace1", "user1")
            } returns createReadCertificateResponse(certificate)
            coEvery { tpmProvider.getRegistrationNumber(certificate) } returns "regNumber"
            coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("22222222-2222-2222-2222-222222222222")
            coEvery { tpmProvider.getOrGenerateClientInstancePublicKey() } returns PublicKeyOut(byteArrayOf(0), Jwk("", "", "", "", "", "", ""))
            coEvery {
                connectorApi.externalAuthenticate(any(), any(), any(), any(), any(), any())
            } throws ConnectorError("SOAP-ENV:Server", "auth failed", "auth failed")

            // when & then
            assertFailsWith<ConnectorError> {
                subjectTokenProvider.createSubjectToken(
                    "clientId", "", "nonce".toByteArray(), "audience",
                    1000L, 30L, tpmProvider,
                )
            }
        }

    @Test
    fun `given subject token when created then readCertificate is called with connector config values`() =
        runTest {
            // given
            setupMocks()

            // when
            subjectTokenProvider.createSubjectToken(
                "clientId", "", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            coVerify {
                connectorApi.readCertificate(
                    "card1", "mandant1", "clientSys1", "workspace1", "user1",
                )
            }
        }

    @Test
    fun `given subject token when created then externalAuthenticate is called with connector config values`() =
        runTest {
            // given
            setupMocks()

            // when
            subjectTokenProvider.createSubjectToken(
                "clientId", "", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            coVerify {
                connectorApi.externalAuthenticate(
                    "card1", "mandant1", "clientSys1", "workspace1", "user1", any(),
                )
            }
        }

    @Test
    fun `given subject token when created then nonce is base64url encoded in claims`() =
        runTest {
            // given
            val nonceBytes = byteArrayOf(10, 20, 30, 40, 50)
            setupMocks()

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "", nonceBytes, "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            val claimsJson = decodeJwtPart(token.split(".")[1])
            val expectedNonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(nonceBytes)
            assertTrue(claimsJson.contains("\"nonce\":\"$expectedNonce\""))
        }

    private fun setupMocks() {
        val certificate = "certificate".toByteArray()
        val signature = joseToDerEcdsa("signature exactly 32 byte length".toByteArray())
        coEvery {
            connectorApi.readCertificate("card1", "mandant1", "clientSys1", "workspace1", "user1")
        } returns createReadCertificateResponse(certificate)
        coEvery { tpmProvider.getRegistrationNumber(certificate) } returns "regNumber"
        coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("22222222-2222-2222-2222-222222222222")
        coEvery {
            connectorApi.externalAuthenticate(any(), any(), any(), any(), any(), any())
        } returns createExternalAuthenticateResponse(signature)

        val mockClientKey = mockk<PublicKeyOut>()
        val mockJwk = mockk<Jwk>()
        coEvery { mockJwk.kid } returns "someKid"
        coEvery { mockClientKey.jwk } returns mockJwk
        coEvery { tpmProvider.getOrGenerateClientInstancePublicKey() } returns mockClientKey
    }

    private fun createReadCertificateResponse(certificate: ByteArray) = ReadCardCertificateResponse(
        Status("OK"),
        X509DataInfoList(
            listOf(
                X509DataInfo(
                    "",
                    X509Data(
                        X509IssuerSerial("", ""),
                        "",
                        certificate.toBase64(true, false),
                    ),
                ),
            ),
        ),
    )

    private fun createExternalAuthenticateResponse(signature: ByteArray) = ExternalAuthenticateResponse(
        Status("OK"),
        SignatureObject(signature.toBase64(true, true)),
    )

    private fun decodeJwtPart(part: String): String {
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .decode(part)
            .decodeToString()
    }
}
