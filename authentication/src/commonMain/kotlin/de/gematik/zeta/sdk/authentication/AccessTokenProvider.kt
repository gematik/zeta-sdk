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

import de.gematik.zeta.sdk.authentication.model.AccessTokenClaims
import de.gematik.zeta.sdk.authentication.model.AccessTokenHeader
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.model.AccessTokenResponse
import de.gematik.zeta.sdk.authentication.model.TokenType
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.tpm.Tpm
import de.gematik.zeta.sdk.tpm.createLongLiveClientKey
import kotlin.io.encoding.Base64
import kotlin.time.Clock.System
import kotlin.uuid.Uuid

interface AccessTokenProvider {
    suspend fun getValidToken(): String
    suspend fun refreshToken(): String
}

@Suppress("FunctionOnlyReturningConstant", "standard:max-line-length")
class AccessTokenProviderImpl(
    private val authApi: AuthenticationApi,
    private val authConfig: AuthConfig,
    private val storage: SdkStorage,
    private val clock: () -> Long = { System.now().epochSeconds },
) : AccessTokenProvider {
    private val authStorage = AuthenticationStorage(storage)

    override suspend fun getValidToken(): String {
        val cached = authStorage.getAccessToken()
        val exp = authStorage.getTokenExpiration()?.toLong()

        if (cached != null && exp != null && exp - clock() > SAFETY_MARGIN_SECS) {
            return cached
        }
        return issueNewAccessToken()
    }

    override suspend fun refreshToken(): String {
        return issueNewAccessToken()
    }

    private suspend fun issueNewAccessToken(): String {
        // 1. nonce → attestation challenge
        // val nonce = authApi.fetchNonce()
        // val attChallenge = calculateAttestationChallenge(nonce)

        // 2. client assertion JWT if your AS requires it
        // val clientAssertion = createClientAssertion()

        // 3. self-made access token (SM(C)-B) to be signed/hashed externally
        // val subjectToken = createSubjectToken(clientId)

        // 4. external signature / proof
        // val signature = authenticateExternal(smcbAccessToken)
        // val tokenWithSig = addSignature(smcbAccessToken, signature)

        // 5 Call AS: token-exchange (adjust fields as needed)

        val clientId: String = "zeta-client"
        val subjectToken: String = "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJXdVo4bFJodHZvb1lxeHExU3A3SHM5ZmU4b2FFSFV6RGNFckRYOUJ2OWhNIn0.eyJleHAiOjE3NTg2MTExNjgsImlhdCI6MTc1ODYxMDg2OCwianRpIjoib25ydHJvOjU5OWNmYjAyLTI0YTktNDViZi0xNDRlLTZjNDg5YTYxMzI2NSIsImlzcyI6Imh0dHA6Ly9sb2NhbGhvc3Q6MTgwODAvcmVhbG1zL3NtYy1iIiwiYXVkIjpbInJlcXVlc3Rlci1jbGllbnQiLCJhY2NvdW50Il0sInN1YiI6ImQwYWFjYzljLTJkOTMtNDM4YS1hNzAzLWI4Nzc4OTIxODNmOCIsInR5cCI6IkJlYXJlciIsImF6cCI6InNtYy1iLWNsaWVudCIsInNpZCI6IjY5ZDgxODA4LTY2ZTYtNDlmMi04OWRiLTdiODBlOGU4OTlmYiIsImFjciI6IjEiLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsiZGVmYXVsdC1yb2xlcy1zbWMtYiIsIm9mZmxpbmVfYWNjZXNzIiwidW1hX2F1dGhvcml6YXRpb24iXX0sInJlc291cmNlX2FjY2VzcyI6eyJhY2NvdW50Ijp7InJvbGVzIjpbIm1hbmFnZS1hY2NvdW50IiwibWFuYWdlLWFjY291bnQtbGlua3MiLCJ2aWV3LXByb2ZpbGUiXX19LCJzY29wZSI6ImVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IlVzZXIgRXh0ZXJuYWwiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJ1c2VyIiwiZ2l2ZW5fbmFtZSI6IlVzZXIiLCJmYW1pbHlfbmFtZSI6IkV4dGVybmFsIiwiZW1haWwiOiJ1c2VyQGJhci5mb28uY29tIn0.sHewe6f5zk_EslSVtectqb_91U_6YpYhQoQhWNFwLINJd3ryrKNaLOeB196x5fbAfFGSk-Exa9D24K64xzETnoKrXQRrRKi4sSJGxDqtXbkmbxr-fJvyB3Ay_0_lCZAUPNEYH2Sx5caClRnJy60eeKt3pm4JmV5nLFXh-DOYEDc5r1NGcl1bwCt70pQJ1aKlMaiUDuC5N8CXSAuUdRc1IWzB324QNBglW4qpUY2anp-j23bnJBhLmYgVeKa_RBksJ1-jSgwODeuO1gIR96qqc7SqjzQVgteGumr5zfR3qc5GAGGBIxYX3Jndr4lqcW2-mYffDwp7fWf4a5FJ5wgUuw"
        val scope: String = "audience-target-scope"
        val clientAssertion: String = "client-jwt"

        val body = AccessTokenRequest(
            grantType = "urn:ietf:params:oauth:grant-type:token-exchange",
            clientId = clientId,
            subjectToken = subjectToken,
            subjectTokenType = "urn:ietf:params:oauth:token-type:jwt",
            scope = scope,
            requestedTokenType = "urn:ietf:params:oauth:token-type:access_token",
            clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            clientAssertion = clientAssertion,
        )

        val resp: AccessTokenResponse = authApi.requestAccessToken(body)
        persist(resp)

        return resp.accessToken
    }

    private suspend fun persist(resp: AccessTokenResponse) {
        val now = System.now().epochSeconds
        val exp = now + resp.expiresIn

        authStorage.saveAccessTokens(resp.accessToken, exp.toInt())
    }

    private suspend fun createClientAssertion(): String {
        // TODO: build JWT for client auth if required by your AS
        // header: alg/kid; claims: iss=sub=clientId, aud=token endpoint, iat/exp/jti
        // sign with client private key (TPM or Keystore)
        TODO("client assertion (jwt-bearer)")
    }

    private fun randomUUID(): ByteArray = Uuid.random().toByteArray()

    private suspend fun createSubjectToken(clientId: String): String {
        val issuer = clientId
        val kid = getHashFromPublicKey()
        val jwk = getSmcbPublicKey()
        val audience = resourceServerAudiences()
        val jkt = base64HashOfDpopPublicKey()
        val scope = effectiveScopes()
        val now = clock()
        val exp = now + authConfig.exp
        val subject = authConfig.sub
        val jti = randomUUID().toString()
        val cnf = AccessTokenClaims.Cnf(jkt)

        return AccessTokenUtility.create(
            AccessTokenHeader(TokenType.ACCESS, kid, jwk),
            AccessTokenClaims(
                issuer = issuer,
                exp = exp,
                audience = audience,
                subject = subject,
                iat = now,
                jti = jti,
                scope = scope,
                cnf = cnf,
            ),
        )
    }

    private suspend fun getHashFromPublicKey(): String {
        val pubKey: ByteArray = createLongLiveClientKey() // TPM-backed or persisted
        val digest = Tpm.provider().hash(pubKey)
        return Base64.encode(digest)
    }

    private fun getSmcbPublicKey(): String {
        // TODO: JWK of your long-lived client key (kty, crv, x, y, kid)
        return ""
    }

    private fun base64HashOfDpopPublicKey(): String {
        // TODO: 'jkt' thumbprint for DPoP key (base64url of SHA-256 JWK thumbprint)
        return ""
    }

    private fun resourceServerAudiences(): List<String> {
        // TODO: from discovery / config
        return emptyList()
    }

    private fun effectiveScopes(): String {
        val configured = authConfig.scopes
        return if (configured.isNotEmpty()) configured.joinToString(" ") else ""
    }

    companion object {
        private const val SAFETY_MARGIN_SECS = 10
    }
}
