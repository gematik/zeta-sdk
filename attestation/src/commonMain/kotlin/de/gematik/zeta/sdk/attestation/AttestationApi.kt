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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.model.ClientAssertionJwt
import de.gematik.zeta.sdk.attestation.model.ClientSelfAssessment
import de.gematik.zeta.sdk.attestation.model.ClientStatement
import de.gematik.zeta.sdk.attestation.model.buildPosture
import de.gematik.zeta.sdk.attestation.model.getPlatform
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * Builds a **client assertion JWT** used to authenticate a client during token exchange
 * and embeds a **client-statement** with an attestation challenge.
 */
interface AttestationApi {

    /**
     * Create a **signed client assertion (JWT)** for the given client and audience, including a
     * **client-statement** with platform posture and an attestation challenge derived from `nonce`.
     *
     * @param productId        Identifier of the calling product/app (used in posture/statement).
     * @param productVersion   Product/app version (used in posture/statement).
     * @param nonce            Server-provided value mixed into the attestation challenge.
     * @param clientId         OAuth 2.0 `client_id` (used as `iss` and `sub`).
     * @param exp              Expiration time in **epoch seconds** for the JWT `exp` claim.
     * @param tokenEndpoint    OAuth/OIDC token endpoint URL (used as `aud`).
     *
     * @return Compact JWS string: `<base64url(header)>.<base64url(payload)>.<base64url(signature)>`.
     */
    suspend fun createClientAssertion(productId: String, productVersion: String, nonce: ByteArray, clientId: String, exp: Long, tokenEndpoint: String, clientSelfAssessment: ClientSelfAssessment): String
}

/**
 * Attestation API that builds a signed client assertion (JWT) and its client-statement.
 *
 * Responsibilities:
 *  - Generate the per-client *instance key* via [TpmProvider].
 *  - Build a client-statement including an attestation challenge:
 *      attChallenge = SHA256( SHA256(clientPubKey) || nonce )
 *  - Emit a compact JWS (header.payload.signature) signed with the client key.
 *
 */
class AttestationApiImpl(
    private val tpmProvider: TpmProvider,
    private val uuidGen: () -> String = { randomUUID() },
    private val clockEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : AttestationApi {
    /**
     * Create a signed client assertion JWT for OAuth token exchange.
     *
     * @param productId       App/product identifier (for client-statement posture).
     * @param productVersion  Version string (for client-statement posture).
     * @param nonce           Server-provided nonce, mixed into the attestation challenge.
     * @param clientId        OAuth client_id (iss/sub).
     * @param exp             Expiration (seconds since epoch).
     * @param tokenEndpoint   Token endpoint URL (aud).
     * @return JWS string.
     */
    override suspend fun createClientAssertion(
        productId: String,
        productVersion: String,
        nonce: ByteArray,
        clientId: String,
        exp: Long,
        tokenEndpoint: String,
        clientSelfAssessment: ClientSelfAssessment,
    ): String {
        Log.d { "Getting client instant keys" }
        val clientInstanceKeys = tpmProvider.generateClientInstanceKey()

        Log.d { "Getting uuid for jti" }
        val jti: String = uuidGen()

        Log.d { "Getting client statement" }
        val statement = getStatement(nonce, clientInstanceKeys.encoded, productId, productVersion, clientId)

        Log.d { "Getting client assertion jwt" }
        val clientAssertion = ClientAssertionJwt(
            header = ClientAssertionJwt.Header(alg = AsymAlg.ES256.name, typ = "JWT", jwk = clientInstanceKeys.jwk),
            payload = ClientAssertionJwt.Payload(clientId, clientId, listOf(tokenEndpoint), exp, jti, Json.encodeToJsonElement(statement), clientSelfAssessment),
        )

        return getJwt(clientAssertion)
    }

    /**
     * Build the client-statement document containing:
     *  - subject (sub)
     *  - platform name
     *  - posture
     *  - attestation timestamp (epoch seconds)
     */
    private suspend fun getStatement(nonce: ByteArray, clientInstancePublicKey: ByteArray, productId: String, productVersion: String, sub: String): ClientStatement {
        val attestationTimestamp: Long = clockEpochSeconds()
        val attChallenge = Base64.encode(calculateAttestationChallenge(nonce, clientInstancePublicKey))
        val posture: JsonElement = buildPosture(productId, productVersion, attChallenge, b64url(clientInstancePublicKey))

        return ClientStatement(sub, getPlatform(), posture, attestationTimestamp)
    }

    /** Create JWS by signing base64url(header) + "." + base64url(payload). */
    private suspend fun getJwt(jwt: ClientAssertionJwt): String {
        val json = Json {
            encodeDefaults = true
        }

        val headerB64 = b64url(json.encodeToString(jwt.header).toByteArray(Charsets.UTF_8))
        val payloadB64 = b64url(json.encodeToString(jwt.payload).toByteArray(Charsets.UTF_8))

        val signInput = "$headerB64.$payloadB64".toByteArray(Charsets.UTF_8)
        val sig = tpmProvider.signWithClientKey(signInput)
        val sigB64 = b64url(sig)

        return "$headerB64.$payloadB64.$sigB64"
    }

    /**
     * Calculate attestation challenge = SHA-256( SHA-256(pubKey) || nonce ).
     *
     * @param nonce     Server-provided nonce.
     * @param publicKey Raw client instance public key bytes.
     */
    private fun calculateAttestationChallenge(nonce: ByteArray, publicKey: ByteArray): ByteArray {
        // Calculate attestation challenge = SHA256(SHA256(pubKey) || nonce)
        Log.d { "Hashing the public key" }
        val publicKeyHash = hashWithSha256(publicKey)

        Log.d { "Calculation of the attestation challenge" }
        val attestationChallenge = ByteArray(publicKeyHash.size + nonce.size)
        publicKeyHash.copyInto(attestationChallenge, destinationOffset = 0)
        nonce.copyInto(attestationChallenge, destinationOffset = publicKeyHash.size)

        Log.d { "Hashing the attestation challenge" }
        return hashWithSha256(attestationChallenge)
    }

    /** Base64URL without padding, URL-safe alphabet. */
    private fun b64url(bytes: ByteArray): String {
        val std = Base64.encode(bytes)
        return std.trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')
    }
}
