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

import Jwk
import PublicKeyOut
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
            setupTpmMocks("certificate".toByteArray(), "signature".toByteArray())
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "dpopKey", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )
            assertEquals(3, token.split(".").size)
        }

    @Test
    fun `given subject token when created then header contains correct typ and alg`() =
        runTest {
            setupTpmMocks("certificate".toByteArray(), "signature".toByteArray())
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "dpopKey", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )
            val headerJson = decodeJwtPart(token.split(".")[0])
            assertTrue(headerJson.contains("\"typ\":\"JWT\""))
            assertTrue(headerJson.contains("\"alg\":\"ES256\""))
        }

    @Test
    fun `given subject token when created then claims contain correct iss and aud`() =
        runTest {
            setupTpmMocks("certificate".toByteArray(), "signature".toByteArray())
            val token = subjectTokenProvider.createSubjectToken(
                "myClientId", "dpopKey", "nonce".toByteArray(), "myAudience",
                1000L, 30L, tpmProvider,
            )
            val claimsJson = decodeJwtPart(token.split(".")[1])
            assertTrue(claimsJson.contains("\"iss\":\"myClientId\""))
            assertTrue(claimsJson.contains("\"myAudience\""))
        }

    @Test
    fun `given subject token when created then exp equals now plus expiration`() =
        runTest {
            val now = 2000L
            val expiration = 60L
            setupTpmMocks("certificate".toByteArray(), "signature".toByteArray())
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "dpopKey", "nonce".toByteArray(), "audience",
                now, expiration, tpmProvider,
            )
            val claimsJson = decodeJwtPart(token.split(".")[1])
            assertTrue(claimsJson.contains("\"exp\":${now + expiration}"))
            assertTrue(claimsJson.contains("\"iat\":$now"))
        }

    @Test
    fun `given tpm readSmbCertificate fails when createSubjectToken then exception propagates`() =
        runTest {
            coEvery {
                tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass")
            } throws RuntimeException("TPM error")

            assertFailsWith<RuntimeException> {
                subjectTokenProvider.createSubjectToken(
                    "clientId", "dpopKey", "nonce".toByteArray(), "audience",
                    1000L, 30L, tpmProvider,
                )
            }
        }

    @Test
    fun `given tpm signWithSmbKey fails when createSubjectToken then exception propagates`() =
        runTest {
            val certificate = "certificate".toByteArray()
            coEvery { tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass") } returns certificate
            coEvery { tpmProvider.getRegistrationNumber(certificate) } returns "regNumber"
            coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("11111111-1111-1111-1111-111111111111")
            coEvery { tpmProvider.signWithSmbKey(any(), "test.p12", "testAlias", "testPass") } throws RuntimeException("Signing error")
            mockClientInstanceKey()

            assertFailsWith<RuntimeException> {
                subjectTokenProvider.createSubjectToken(
                    "clientId", "dpopKey", "nonce".toByteArray(), "audience",
                    1000L, 30L, tpmProvider,
                )
            }
        }

    @Test
    fun `given subject token when created then tpm is called with correct keystore parameters`() =
        runTest {
            setupTpmMocks("certificate".toByteArray(), "signature".toByteArray())
            subjectTokenProvider.createSubjectToken(
                "clientId", "dpopKey", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )
            coVerify { tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass") }
            coVerify { tpmProvider.signWithSmbKey(any(), "test.p12", "testAlias", "testPass") }
        }

    @Test
    fun `given subject token when created then signature is appended as url-safe base64`() =
        runTest {
            val signature = "mySig123".toByteArray()
            setupTpmMocks("certificate".toByteArray(), signature)
            val token = subjectTokenProvider.createSubjectToken(
                "clientId", "dpopKey", "nonce".toByteArray(), "audience",
                1000L, 30L, tpmProvider,
            )
            assertEquals(signature.toBase64(false, false), token.split(".")[2])
        }

    private fun mockClientInstanceKey(kid: String = "someKid") {
        val mockClientKey = mockk<PublicKeyOut>()
        val mockJwk = mockk<Jwk>()
        coEvery { mockJwk.kid } returns kid
        coEvery { mockClientKey.jwk } returns mockJwk
        coEvery { tpmProvider.getOrGenerateClientInstancePublicKey() } returns mockClientKey
    }

    private fun setupTpmMocks(certificate: ByteArray, signature: ByteArray) {
        coEvery { tpmProvider.readSmbCertificate("test.p12", "testAlias", "testPass") } returns certificate
        coEvery { tpmProvider.getRegistrationNumber(any()) } returns "regNumber"
        coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("11111111-1111-1111-1111-111111111111")
        coEvery { tpmProvider.signWithSmbKey(any(), "test.p12", "testAlias", "testPass") } returns signature
        mockClientInstanceKey()
    }

    private fun decodeJwtPart(part: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(part).decodeToString()
}
