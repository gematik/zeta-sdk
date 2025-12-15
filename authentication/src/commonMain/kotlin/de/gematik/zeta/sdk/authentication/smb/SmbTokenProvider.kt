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

import de.gematik.zeta.sdk.authentication.AccessTokenUtility
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.authentication.model.AccessTokenClaims
import de.gematik.zeta.sdk.authentication.model.AccessTokenHeader
import de.gematik.zeta.sdk.authentication.model.TokenType
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.utils.io.core.toByteArray
import kotlin.io.encoding.Base64

class SmbTokenProvider(
    private val smbKeystore: Credentials,
) : SubjectTokenProvider {
    override suspend fun createSubjectToken(
        clientId: String,
        nonceBytes: ByteArray,
        audience: String,
        now: Long,
        expiration: Long,
        tpmProvider: TpmProvider,
    ): String {
        val smbCertificate = tpmProvider.readSmbCertificate(
            smbKeystore.keystoreFile,
            smbKeystore.alias,
            smbKeystore.password,
        )

        val kid = getHashFromSmbCertificate(smbCertificate)
        val x5c = listOf(Base64.encode(smbCertificate))
        val nonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(nonceBytes)
        val exp = now + expiration
        val aud = listOf(audience)
        val sub = tpmProvider.getRegistrationNumber(smbCertificate)
        val jti = tpmProvider.randomUuid().toString()

        val subjectToken = AccessTokenUtility.create(
            AccessTokenHeader(
                typ = TokenType.JWT,
                kid = kid,
                x5c = x5c,
                alg = AsymAlg.ES256.name,
            ),
            AccessTokenClaims(
                iss = clientId,
                exp = exp,
                aud = aud,
                sub = sub,
                iat = now,
                nonce = nonce,
                jti = jti,
                typ = "Bearer",
            ),
        )
        return AccessTokenUtility.addSignature(subjectToken, signSmbToken(tpmProvider, subjectToken))
    }

    private fun getHashFromSmbCertificate(certificate: ByteArray): String {
        val digest = hashWithSha256(certificate)
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(digest)
    }

    private suspend fun signSmbToken(tpmProvider: TpmProvider, token: String): String {
        val signature = tpmProvider.signWithSmbKey(
            token.toByteArray(),
            smbKeystore.keystoreFile,
            smbKeystore.alias,
            smbKeystore.password,
        )
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(signature)
    }

    public data class Credentials(
        val keystoreFile: String,
        val alias: String,
        val password: String,
    )
}
