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
import de.gematik.zeta.platform.getPlatformInfo
import de.gematik.zeta.sdk.attestation.AttestationResponse
import de.gematik.zeta.sdk.attestation.AttestationService
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.ClientAssertionJwt
import de.gematik.zeta.sdk.attestation.model.ClientSelfAssessment
import de.gematik.zeta.sdk.attestation.model.ClientStatement
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.attestation.model.PostureType
import de.gematik.zeta.sdk.attestation.model.TpmPosture
import de.gematik.zeta.sdk.attestation.model.buildPosture
import de.gematik.zeta.sdk.attestation.model.getPlatform
import de.gematik.zeta.sdk.attestation.model.getPostureType
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.Base64.PaddingOption
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
    suspend fun createClientAssertion(productId: String, productVersion: String, nonce: ByteArray, clientId: String, exp: Long, tokenEndpoint: String, platformProductId: PlatformProductId): String
}

/**
 * Attestation API that builds a signed client assertion (JWT) and its client-statement.
 *
 * Responsibilities:
 *  - Generate the per-client *instance key* via [TpmProvider].
 *  - Build a client-statement including an attestation challenge:
 *      attChallenge = SHA256( jwkThumbprint || nonce )
 *  - Emit a compact JWS (header.payload.signature) signed with the client key.
 *
 */
class AttestationApiImpl(
    private val tpmProvider: TpmProvider,
    private val uuidGen: () -> String = { randomUUID() },
    private val clockEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val attestationConfig: AttestationConfig = AttestationConfig.software(),
) : AttestationApi {
    val json = Json {
        encodeDefaults = true
    }
    private val attestationService: AttestationService? by lazy {
        attestationConfig.getAttestationService(ZetaHttpClientBuilder().build())
    }

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
        platformProductId: PlatformProductId,
    ): String {
        Log.i { "Getting client instant keys" }
        val clientInstanceKeys = tpmProvider.getOrGenerateClientInstancePublicKey()

        Log.i { "Getting uuid for jti" }
        val jti: String = uuidGen()

        val thumbprint = getThumbprint(clientInstanceKeys.jwk.kid)
        val attChallenge = calculateAttestationChallenge(nonce, thumbprint)

        val statementJson = when (attestationConfig) {
            is AttestationConfig.Software -> {
                Log.i { "Getting software client statement" }
                getSoftwareStatement(
                    attChallenge,
                    clientInstanceKeys.encoded,
                    productId,
                    productVersion,
                    clientId,
                    platformProductId,
                )
            }

            is AttestationConfig.TpmHttp,
            is AttestationConfig.TpmCustom,
            -> {
                Log.i { "Generating TPM attestation statement" }
                getTpmStatement(attChallenge, clientId, productId, clientId, platformProductId)
            }
        }

        Log.i { "Getting client assertion jwt" }
        val clientAssertion = ClientAssertionJwt(
            header = ClientAssertionJwt.Header(alg = AsymAlg.ES256.name, typ = "JWT", jwk = clientInstanceKeys.jwk),
            payload = ClientAssertionJwt.Payload(clientId, clientId, listOf(tokenEndpoint), exp, jti, attestationConfig.type, statementJson, ClientSelfAssessment("", clientId, "", "", "test@emailtest.de", clockEpochSeconds(), platformProductId)),
        )

        return getJwt(clientAssertion)
    }

    private fun getThumbprint(kid: String): ByteArray {
        return Base64.UrlSafe
            .withPadding(PaddingOption.ABSENT)
            .decode(kid)
    }

    /**
     * Build the client-statement document containing:
     *  - subject (sub)
     *  - platform name
     *  - posture
     *  - attestation timestamp (epoch seconds)
     */
    private suspend fun getSoftwareStatement(
        attestationChallenge: ByteArray,
        encoded: ByteArray,
        productId: String,
        productVersion: String,
        sub: String,
        platformProductId: PlatformProductId,
    ): ClientStatement {
        val publicKey = Base64.encode(encoded)
        val attestationTimestamp: Long = clockEpochSeconds()
        val posture: JsonElement = buildPosture(platformProductId, productId, productVersion, b64url(attestationChallenge), publicKey)
        val postureType = getPostureType()
        return ClientStatement(sub, getPlatform(), postureType, posture, attestationTimestamp)
    }

    private suspend fun getTpmStatement(
        attestationChallenge: ByteArray,
        productId: String,
        productVersion: String,
        sub: String,
        platformProductId: PlatformProductId,
    ): ClientStatement {
        val service = attestationService
            ?: error("Attestation not configured")

        val attestationResponse = service.generateAttestation(Base64.encode(attestationChallenge))
        attestationResponse.error?.let {
            error(attestationResponse.error.message)
        }

        val attestationTimestamp: Long = clockEpochSeconds()

        val tpmPosture = buildTpmPosture(attestationResponse, platformProductId, productId, productVersion)

        return ClientStatement(
            sub = sub,
            platform = getPlatform(),
            postureType = PostureType.TPM,
            posture = tpmPosture,
            attestationTimestamp = attestationTimestamp,
        )
    }

    private fun buildTpmPosture(
        response: AttestationResponse,
        platformProductId: PlatformProductId,
        productId: String,
        productVersion: String,
    ): JsonElement {
        val platformInfo = getPlatformInfo()
        val os = platformInfo.os
        val osVersion = platformInfo.osVersion
        val arch = platformInfo.arch

        return json.encodeToJsonElement(
            TpmPosture.serializer(),
            TpmPosture(
                platformProductId,
                productId,
                productVersion,
                os,
                osVersion,
                arch,
                response.attestationKey,
                response.tpmQuote,
                // response.tpmQuoteSignature,
                response.eventLog ?: "",
                response.certificateChain,
            ),
        )
    }

    /** Create JWS by signing base64url(header) + "." + base64url(payload). */
    private suspend fun getJwt(jwt: ClientAssertionJwt): String {
        val headerB64 = b64url(json.encodeToString(jwt.header).toByteArray(Charsets.UTF_8))

        val payloadJson = buildJsonObject {
            put("iss", jwt.payload.iss)
            put("sub", jwt.payload.sub)
            putJsonArray("aud") {
                jwt.payload.aud.forEach { add(it) }
            }
            put("exp", jwt.payload.exp)
            put("jti", jwt.payload.jti)

            jwt.payload.clientStatement.let { statement ->
                put(jwt.payload.attestationType.getClaimName(), json.encodeToJsonElement(statement))
            }
            put("urn:telematik:client-self-assessment", json.encodeToJsonElement(jwt.payload.clientSelfAssessment))
        }

        val payloadB64 = b64url(payloadJson.toString().toByteArray(Charsets.UTF_8))

        val signInput = "$headerB64.$payloadB64".toByteArray(Charsets.UTF_8)
        val sig = tpmProvider.signWithClientKey(signInput)
        val sigB64 = b64url(sig)

        return "$headerB64.$payloadB64.$sigB64"
    }

    /**
     * Calculate attestation challenge = SHA-256(jwkThumbprint || nonce).
     *
     * @param nonce       Server-provided nonce.
     * @param thumbprint  JWK thumbprint (SHA-256 of canonical JWK).
     */
    private fun calculateAttestationChallenge(nonce: ByteArray, thumbprint: ByteArray): ByteArray {
        val combined = ByteArray(thumbprint.size + nonce.size)
        thumbprint.copyInto(combined, destinationOffset = 0)
        nonce.copyInto(combined, destinationOffset = thumbprint.size)

        val attestationChallenge = hashWithSha256(combined)

        return attestationChallenge
    }

    /** Base64URL without padding, URL-safe alphabet. */
    private fun b64url(bytes: ByteArray): String {
        return Base64.UrlSafe
            .withPadding(PaddingOption.ABSENT)
            .encode(bytes)
    }
}
