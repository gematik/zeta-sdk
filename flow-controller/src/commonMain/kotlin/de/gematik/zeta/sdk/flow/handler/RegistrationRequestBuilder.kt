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
package de.gematik.zeta.sdk.flow.handler

import Jwk
import de.gematik.zeta.sdk.attestation.DeviceAttestationEvidence
import de.gematik.zeta.sdk.clientregistration.model.AndroidAttestation
import de.gematik.zeta.sdk.clientregistration.model.AppleAttestation
import de.gematik.zeta.sdk.clientregistration.model.ClientMetadata
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.clientregistration.model.SoftwareAttestation
import de.gematik.zeta.sdk.clientregistration.model.ZetaTokenAttestation
import kotlin.io.encoding.Base64

private const val GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"

// dcr-request.yaml 1.2.4 definitions/grant_types: form (1) for mobile clients, form (3) for institution clients
private val MOBILE_GRANT_TYPES = listOf("authorization_code", GRANT_TYPE_TOKEN_EXCHANGE, "refresh_token")
private val INSTITUTION_GRANT_TYPES = listOf(GRANT_TYPE_TOKEN_EXCHANGE, "refresh_token")
private const val TOKEN_ENDPOINT_AUTH_METHOD = "private_key_jwt"

private fun metadata(clientName: String, clientKeyJwk: Jwk, redirectUris: List<String>) = ClientMetadata(
    tokenEndpointAuthMethod = TOKEN_ENDPOINT_AUTH_METHOD,
    // Only mobile clients have redirect URIs; the guard tells the two apart the same way.
    grantTypes = if (redirectUris.isEmpty()) INSTITUTION_GRANT_TYPES else MOBILE_GRANT_TYPES,
    clientName = clientName,
    redirectUris = redirectUris,
    jwks = Jwks(listOf(clientKeyJwk)),
)

private fun popB64Url(pop: ByteArray) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(pop)

private val base64UrlDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

// The message a software proof of possession signs, in the form the deployed guard checks it
// (keycloak-zeta PKIUtilKt.toClientHashData): 0x04 || X || Y || base64url-decoded nonce, signed with
// SHA256withECDSA. dcr-request.yaml 1.2.4 defines another form (canonical JWK, UTF-8 nonce, hashed first).
internal fun softwareProofMessage(clientKeyJwk: Jwk, nonce: String): ByteArray =
    byteArrayOf(0x04) + base64UrlDecoder.decode(clientKeyJwk.x) + base64UrlDecoder.decode(clientKeyJwk.y) + base64UrlDecoder.decode(nonce)

// Abb-ZETA-DCR-für-mobile-Clients (12) software registration, dcr-request.yaml 1.2.4 registration_software
internal fun buildSoftwareRegistrationRequest(
    clientName: String,
    clientKeyJwk: Jwk,
    nonce: String,
    signedHashPukClientSig: ByteArray,
    redirectUris: List<String>,
) = ClientRegistrationRequest.Software(
    metadata(clientName, clientKeyJwk, redirectUris),
    SoftwareAttestation(nonce = nonce, signedHashPukClientSig = popB64Url(signedHashPukClientSig)),
)

internal fun buildHardwareRegistrationRequest(
    clientName: String,
    clientKeyJwk: Jwk,
    evidence: DeviceAttestationEvidence,
    signedHashPukClientSig: ByteArray,
    redirectUris: List<String>,
): ClientRegistrationRequest = when (evidence) {
    // Abb-ZETA-DCR-für-mobile-Clients (01) Apple App Attest registration
    is DeviceAttestationEvidence.Apple -> ClientRegistrationRequest.Apple(
        metadata(clientName, clientKeyJwk, redirectUris),
        AppleAttestation(
            appleAttestationObject = Base64.encode(evidence.attestationObject),
            signedHashPukClientSig = popB64Url(signedHashPukClientSig),
        ),
    )
    // Abb-ZETA-DCR-für-mobile-Clients (04) Android Key Attestation registration
    is DeviceAttestationEvidence.Android -> ClientRegistrationRequest.Android(
        metadata(clientName, clientKeyJwk, redirectUris),
        AndroidAttestation(
            keyAttestationCertificateChain = evidence.keyAttestationCertificateChain.map { Base64.encode(it) },
            signedHashPukClientSig = popB64Url(signedHashPukClientSig),
            playIntegrityToken = evidence.playIntegrityToken,
        ),
    )
}

// Abb-ZETA-DCR-für-mobile-Clients (15) fast path with ZETA attestation token
internal fun buildFastPathRegistrationRequest(
    clientName: String,
    clientKeyJwk: Jwk,
    zetaAttestationToken: String,
    signedHashPukClientSig: ByteArray,
    attestationPop: ByteArray,
    redirectUris: List<String>,
) = ClientRegistrationRequest.ZetaAttestationToken(
    metadata(clientName, clientKeyJwk, redirectUris),
    ZetaTokenAttestation(
        zetaAttestationToken = zetaAttestationToken,
        signedHashPukClientSig = popB64Url(signedHashPukClientSig),
        attestationPop = Base64.encode(attestationPop),
    ),
)
