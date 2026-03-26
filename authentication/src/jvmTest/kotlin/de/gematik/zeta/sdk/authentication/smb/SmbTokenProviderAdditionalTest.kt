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

package de.gematik.zeta.sdk.authentication.smb

import de.gematik.zeta.sdk.authentication.toBase64
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SmbTokenProviderAdditionalTest {

    private val tpmProvider = mockk<TpmProvider>()

    private val credentials = SmbTokenProvider.Credentials(
        keystoreFile = "test.p12",
        alias = "testAlias",
        password = "testPass",
    )

    private val subjectTokenProvider = SmbTokenProvider(smbKeystore = credentials)

    @Test
    fun `given subject token when created then token has three parts separated by dots`() =
        runTest {
            // given
            val certificate = "certificate".toByteArray()
            val signature = "signature".toByteArray()
            setupTpmMocks(certificate, signature)

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "nonce".toByteArray(), "audience",
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
            val certificate = "certificate".toByteArray()
            val signature = "signature".toByteArray()
            setupTpmMocks(certificate, signature)

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            val headerJson = decodeJwtPart(token.split(".")[0])
            assertTrue(headerJson.contains("\"typ\":\"JWT\""))
            assertTrue(headerJson.contains("\"alg\":\"ES256\""))
        }

    @Test
    fun `given subject token when created then claims contain correct iss and aud`() =
        runTest {
            // given
            val certificate = "certificate".toByteArray()
            val signature = "signature".toByteArray()
            setupTpmMocks(certificate, signature)

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "myClientId", "nonce".toByteArray(), "myAudience",
                1000L, 30L, tpmProvider,
            )

            // then
            val claimsJson = decodeJwtPart(token.split(".")[1])
            assertTrue(claimsJson.contains("\"iss\":\"myClientId\""))
            assertTrue(claimsJson.contains("\"myAudience\""))
        }

    @Test
    fun `given subject token when created then exp equals now plus expiration`() =
        runTest {
            // given
            val certificate = "certificate".toByteArray()
            val signature = "signature".toByteArray()
            val now = 2000L
            val expiration = 60L
            setupTpmMocks(certificate, signature)

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "nonce".toByteArray(), "audience",
                now, expiration, tpmProvider,
            )

            // then
            val claimsJson = decodeJwtPart(token.split(".")[1])
            assertTrue(claimsJson.contains("\"exp\":${now + expiration}"))
            assertTrue(claimsJson.contains("\"iat\":$now"))
        }

    @Test
    fun `given tpm readSmbCertificate fails when createSubjectToken then exception propagates`() =
        runTest {
            // given
            coEvery {
                tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass")
            } throws RuntimeException("TPM error")

            // when & then
            assertFailsWith<RuntimeException> {
                subjectTokenProvider.createSubjectToken(
                    "clientId", "nonce".toByteArray(), "audience",
                    1000L, 30L, tpmProvider,
                )
            }
        }

    @Test
    fun `given tpm signWithSmbKey fails when createSubjectToken then exception propagates`() =
        runTest {
            // given
            val certificate = "certificate".toByteArray()
            coEvery {
                tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass")
            } returns certificate
            coEvery { tpmProvider.getRegistrationNumber(certificate) } returns "regNumber"
            coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("11111111-1111-1111-1111-111111111111")
            coEvery {
                tpmProvider.signWithSmbKey(any(), "test.p12", "testAlias", "testPass")
            } throws RuntimeException("Signing error")

            // when & then
            assertFailsWith<RuntimeException> {
                subjectTokenProvider.createSubjectToken(
                    "clientId", "nonce".toByteArray(), "audience",
                    1000L, 30L, tpmProvider,
                )
            }
        }

    @Test
    fun `given subject token when created then tpm is called with correct keystore parameters`() =
        runTest {
            // given
            val certificate = "certificate".toByteArray()
            val signature = "signature".toByteArray()
            setupTpmMocks(certificate, signature)

            // when
            subjectTokenProvider.createSubjectToken(
                "clientId", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            coVerify { tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass") }
            coVerify { tpmProvider.signWithSmbKey(any(), "test.p12", "testAlias", "testPass") }
        }

    @Test
    fun `given subject token when created then signature is appended as url-safe base64`() =
        runTest {
            // given
            val certificate = "certificate".toByteArray()
            val signature = "mySig123".toByteArray()
            setupTpmMocks(certificate, signature)

            // when
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )

            // then
            val signaturePart = token.split(".")[2]
            assertEquals(signature.toBase64(false, false), signaturePart)
        }

    private fun setupTpmMocks(certificate: ByteArray, signature: ByteArray) {
        coEvery {
            tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass")
        } returns certificate
        coEvery { tpmProvider.getRegistrationNumber(any()) } returns "regNumber"
        coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("11111111-1111-1111-1111-111111111111")
        coEvery {
            tpmProvider.signWithSmbKey(any(), "test.p12", "testAlias", "testPass")
        } returns signature
    }

    private fun decodeJwtPart(part: String): String {
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .decode(part)
            .decodeToString()
    }
}
