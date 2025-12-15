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
import derEcdsaToJose
import io.mockk.coEvery
import io.mockk.mockk
import joseToDerEcdsa
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SmcbTokenProviderTest {

    private val tpmProvider = mockk<TpmProvider>()

    private val connectorApi = mockk<ConnectorApi>()

    private val subjectTokenProvider = SmcbTokenProvider(
        connectorConfig = SmcbTokenProvider.ConnectorConfig(
            baseUrl = "baseUrl",
            mandantId = "mandantId",
            clientSystemId = "clientSystemId",
            workspaceId = "workspaceId",
            userId = "userId",
            cardHandle = "cardHandle",
        ),
        connectorApi = connectorApi,
    )

    @Test
    fun `given SMC-B input data for subject token when create subject token then should return valid token`() =
        runTest {
            // given
            val clientId = "clientId"
            val nonce = "nonce".toByteArray()
            val audience = "audience"
            val now = MOCK_TIMESTAMP
            val expiration = 30L

            val tokenPayload = "eyJ0eXAiOiJKV1QiLCJraWQiOiJBOVp0MElnMXdjb19Fb3pPck5IekdzbEJZd2xySVBSRnJvUW9XOENETFhJIiwieDVjIjpbIlkyVnlkR2xtYVdOaGRHVT0iXSwiYWxnIjoiRVMyNTYifQ.eyJpc3MiOiJjbGllbnRJZCIsImV4cCI6MTc2Mjc3OTYzMSwiYXVkIjpbImF1ZGllbmNlIl0sInN1YiI6InJlZ051bWJlciIsImlhdCI6MTc2Mjc3OTYwMSwibm9uY2UiOiJibTl1WTJVIiwianRpIjoiNDVlZmI3ZjgtZWYwMC00ZWM3LTlkZjgtNTQ1ZGVjNjAyZGM4IiwidHlwIjoiQmVhcmVyIn0"
            val challenge = "sbQizgML6hOZ5+3Y6uv6UaNx3pFoAkTJ8k17BrRBDPk"
            val signature = joseToDerEcdsa("signature exactly 32 byte length".toByteArray())
            val certificate = "certificate".toByteArray()

            coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("45efb7f8-ef00-4ec7-9df8-545dec602dc8")
            coEvery { tpmProvider.getRegistrationNumber(certificate) } returns "regNumber"
            coEvery {
                connectorApi.readCertificate(
                    "cardHandle",
                    "mandantId",
                    "clientSystemId",
                    "workspaceId",
                    "userId",
                )
            } returns createReadCertificateResponse(certificate)
            coEvery {
                connectorApi.externalAuthenticate(
                    "cardHandle",
                    "mandantId",
                    "clientSystemId",
                    "workspaceId",
                    "userId",
                    challenge,
                )
            } returns createExternalAuthenticateResponse(signature)

            // when
            val token = subjectTokenProvider.createSubjectToken(
                clientId,
                nonce,
                audience,
                now,
                expiration,
                tpmProvider,
            )

            // then
            assertEquals(
                "$tokenPayload.${derEcdsaToJose(signature).toBase64(false, false)}",
                token,
            )
        }

    fun createReadCertificateResponse(certificate: ByteArray) = ReadCardCertificateResponse(
        Status("OK"),
        X509DataInfoList(
            listOf(
                X509DataInfo(
                    "",
                    X509Data(
                        X509IssuerSerial(
                            "",
                            "",
                        ),
                        "",
                        certificate.toBase64(true, false),
                    ),
                ),
            ),
        ),
    )

    fun createExternalAuthenticateResponse(signature: ByteArray) = ExternalAuthenticateResponse(
        Status("OK"),
        SignatureObject(
            signature.toBase64(true, true),
        ),
    )

    companion object Companion {
        const val MOCK_TIMESTAMP = 1762779601L
    }
}
