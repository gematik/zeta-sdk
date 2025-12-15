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

import AsymAlg
import de.gematik.zeta.sdk.authentication.AccessTokenUtility
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.authentication.model.AccessTokenClaims
import de.gematik.zeta.sdk.authentication.model.AccessTokenHeader
import de.gematik.zeta.sdk.authentication.model.TokenType
import de.gematik.zeta.sdk.authentication.smcb.model.ReadCardCertificateResponse
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.tpm.TpmProvider
import derEcdsaToJose
import io.ktor.utils.io.core.toByteArray
import kotlin.io.encoding.Base64

class SmcbTokenProvider(
    private val connectorConfig: ConnectorConfig,
    private val connectorApi: ConnectorApi = ConnectorApiImpl(connectorConfig),
) : SubjectTokenProvider {

    override suspend fun createSubjectToken(clientId: String, nonceBytes: ByteArray, audience: String, now: Long, expiration: Long, tpmProvider: TpmProvider): String {
        val response = connectorApi.readCertificate(
            connectorConfig.cardHandle,
            connectorConfig.mandantId,
            connectorConfig.clientSystemId,
            connectorConfig.workspaceId,
            connectorConfig.userId,
        )

        val smbcCertificate = getSmcbCertificate(response)

        val kid = getHashFromSmcbCertificate(smbcCertificate)
        val x5c = listOf(Base64.encode(smbcCertificate))
        val nonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(nonceBytes)
        val exp = now + expiration
        val aud = listOf(audience)
        val sub = tpmProvider.getRegistrationNumber(smbcCertificate)
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
        return AccessTokenUtility.addSignature(subjectToken, signSmcbToken(subjectToken))
    }

    private fun getSmcbCertificate(response: ReadCardCertificateResponse): ByteArray {
        val certificate = response.x509DataInfoList.x509DataInfo.firstOrNull()?.x509Data?.x509Certificate.orEmpty()
        return Base64.decode(certificate)
    }

    private fun getHashFromSmcbCertificate(certificate: ByteArray): String {
        val digest = hashWithSha256(certificate)
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(digest)
    }

    private suspend fun signSmcbTokenHash(tokenHash: String): ByteArray {
        val signature = connectorApi.externalAuthenticate(
            connectorConfig.cardHandle,
            connectorConfig.mandantId,
            connectorConfig.clientSystemId,
            connectorConfig.workspaceId,
            connectorConfig.userId,
            tokenHash,
        )
        return Base64.decode(signature.signatureObject.base64Signature)
    }

    private suspend fun signSmcbToken(token: String): String {
        val digest = hashWithSha256(token.toByteArray())
        val challenge = Base64.withPadding(Base64.PaddingOption.ABSENT).encode(digest)
        val signature = derEcdsaToJose(signSmcbTokenHash(challenge), 32)
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(signature)
    }

    data class ConnectorConfig(
        val baseUrl: String,
        val mandantId: String,
        val clientSystemId: String,
        val workspaceId: String,
        val userId: String,
        val cardHandle: String,
    )
}
