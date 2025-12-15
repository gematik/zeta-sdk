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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SmbTokenProviderTest {

    private val tpmProvider = mockk<TpmProvider>()

    private val subjectTokenProvider = SmbTokenProvider(
        smbKeystore = SmbTokenProvider.Credentials(
            keystoreFile = "keystore",
            alias = "alias",
            password = "password",
        ),
    )

    @Test
    fun `given SM-B input data for subject token when create subject token then should return valid token`() =
        runTest {
            // given
            val clientId = "clientId"
            val nonce = "nonce".toByteArray()
            val audience = "audience"
            val now = MOCK_TIMESTAMP
            val expiration = 30L

            val tokenPayload = "eyJ0eXAiOiJKV1QiLCJraWQiOiJBOVp0MElnMXdjb19Fb3pPck5IekdzbEJZd2xySVBSRnJvUW9XOENETFhJIiwieDVjIjpbIlkyVnlkR2xtYVdOaGRHVT0iXSwiYWxnIjoiRVMyNTYifQ.eyJpc3MiOiJjbGllbnRJZCIsImV4cCI6MTc2Mjc3OTYzMSwiYXVkIjpbImF1ZGllbmNlIl0sInN1YiI6InJlZ051bWJlciIsImlhdCI6MTc2Mjc3OTYwMSwibm9uY2UiOiJibTl1WTJVIiwianRpIjoiNDVlZmI3ZjgtZWYwMC00ZWM3LTlkZjgtNTQ1ZGVjNjAyZGM4IiwidHlwIjoiQmVhcmVyIn0"
            val signature = "signature".toByteArray()
            val certificate = "certificate".toByteArray()

            coEvery { tpmProvider.randomUuid() } returns Uuid.parseHexDash("45efb7f8-ef00-4ec7-9df8-545dec602dc8")
            coEvery { tpmProvider.getRegistrationNumber(any()) } returns "regNumber"
            coEvery {
                tpmProvider.readSmbCertificate(
                    "keystore",
                    "alias",
                    "password",
                )
            } returns certificate
            coEvery {
                tpmProvider.signWithSmbKey(
                    tokenPayload.toByteArray(),
                    "keystore",
                    "alias",
                    "password",
                )
            } returns signature

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
                "$tokenPayload.${signature.toBase64(false, false)}",
                token,
            )
        }

    companion object Companion {
        const val MOCK_TIMESTAMP = 1762779601L
    }
}
