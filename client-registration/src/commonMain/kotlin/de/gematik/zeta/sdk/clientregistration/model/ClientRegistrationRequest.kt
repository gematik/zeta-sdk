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

package de.gematik.zeta.sdk.clientregistration.model

import Jwk
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** DCR request body (RFC 7591, dcr-request.yaml 1.2.4); [jwks] carries the TPM client instance public key. */
@Serializable
data class ClientMetadata(
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
    @SerialName("grant_types") val grantTypes: List<String>,
    @SerialName("client_name") val clientName: String,
    /** Left out when empty: the schema wants at least one entry, and institution clients have none. */
    @SerialName("redirect_uris") val redirectUris: List<String> = emptyList(),
    val jwks: Jwks,
)

/** Abb-ZETA-DCR-für-mobile-Clients (12) evidence: proof of possession only (dcr-request.yaml 1.2.4 registration_software). */
@Serializable
data class SoftwareAttestation(
    /** Nonce from the guard's nonce endpoint. */
    val nonce: String,
    /** DER SHA256withECDSA signature over 0x04 || X || Y || base64url-decoded nonce (the guard's current form), base64url without padding. */
    @SerialName("signed_hash_puk_client_sig") val signedHashPukClientSig: String,
) {
    @Required
    @SerialName("attestation_type")
    val attestationType: String = ClientRegistrationRequest.ATTESTATION_TYPE_SOFTWARE
}

/** Abb-ZETA-DCR-für-mobile-Clients (01) evidence: Apple App Attest. */
@Serializable
data class AppleAttestation(
    /** CBOR attestation object from DCAppAttestService.attestKey, standard base64. */
    @SerialName("apple_attestation_object") val appleAttestationObject: String,
    /** DER ECDSA signature over SHA-256(PuK.Client.Sig) with PrK.Client.Sig, base64url without padding. */
    @SerialName("signed_hash_puk_client_sig") val signedHashPukClientSig: String,
) {
    @Required
    @SerialName("attestation_type")
    val attestationType: String = ClientRegistrationRequest.ATTESTATION_TYPE_APPLE
}

/** Abb-ZETA-DCR-für-mobile-Clients (04) evidence: Android Key Attestation. */
@Serializable
data class AndroidAttestation(
    /** DER certificates of Alias_AK, leaf first, standard base64. */
    @SerialName("android_key_attestation_certificate_chain") val keyAttestationCertificateChain: List<String>,
    /** DER ECDSA signature over SHA-256(PuK.Client.Sig) with PrK.Client.Sig, base64url without padding. */
    @SerialName("signed_hash_puk_client_sig") val signedHashPukClientSig: String,
    /** Google Play Integrity classic token bound to hash_puk_client_sig, as returned by Google; absent when unavailable. */
    @SerialName("play_integrity_token") val playIntegrityToken: String? = null,
) {
    @Required
    @SerialName("attestation_type")
    val attestationType: String = ClientRegistrationRequest.ATTESTATION_TYPE_ANDROID
}

/** Abb-ZETA-DCR-für-mobile-Clients (15) evidence: ZETA attestation token from a previous hardware attestation plus fresh proofs. */
@Serializable
data class ZetaTokenAttestation(
    @SerialName("zeta_attestation_token") val zetaAttestationToken: String,
    /** DER ECDSA signature over SHA-256(PuK.Client.Sig) with PrK.Client.Sig, base64url without padding. */
    @SerialName("signed_hash_puk_client_sig") val signedHashPukClientSig: String,
    /** Fresh proof of the attestation key (Apple: base64 CBOR assertion over SHA-256(PuK)). */
    @SerialName("attestation_pop") val attestationPop: String,
) {
    @Required
    @SerialName("attestation_type")
    val attestationType: String = ClientRegistrationRequest.ATTESTATION_TYPE_ZETA_TOKEN
}

/**
 * DCR request, one variant per registration path of the ZETA DCR diagram. On the wire the
 * [metadata] and the variant's attestation part form a single flat JSON object.
 */
sealed interface ClientRegistrationRequest {
    val metadata: ClientMetadata

    /** Abb-ZETA-DCR-für-mobile-Clients (12): software registration, client metadata plus proof of possession. */
    data class Software(override val metadata: ClientMetadata, val attestation: SoftwareAttestation) : ClientRegistrationRequest

    /** Abb-ZETA-DCR-für-mobile-Clients (01): Apple App Attest registration. */
    data class Apple(override val metadata: ClientMetadata, val attestation: AppleAttestation) : ClientRegistrationRequest

    /** Abb-ZETA-DCR-für-mobile-Clients (04): Android Key Attestation registration. */
    data class Android(override val metadata: ClientMetadata, val attestation: AndroidAttestation) : ClientRegistrationRequest

    /** Abb-ZETA-DCR-für-mobile-Clients (15): fast path with a ZETA attestation token. */
    data class ZetaAttestationToken(override val metadata: ClientMetadata, val attestation: ZetaTokenAttestation) : ClientRegistrationRequest

    companion object {
        const val ATTESTATION_TYPE_SOFTWARE = "software"
        const val ATTESTATION_TYPE_APPLE = "apple"
        const val ATTESTATION_TYPE_ANDROID = "android"
        const val ATTESTATION_TYPE_ZETA_TOKEN = "zeta_attestation_token"
    }
}

fun ClientRegistrationRequest.toJson(): JsonObject {
    val attestation = when (this) {
        is ClientRegistrationRequest.Software -> Json.encodeToJsonElement(attestation).jsonObject
        is ClientRegistrationRequest.Apple -> Json.encodeToJsonElement(attestation).jsonObject
        is ClientRegistrationRequest.Android -> Json.encodeToJsonElement(attestation).jsonObject
        is ClientRegistrationRequest.ZetaAttestationToken -> Json.encodeToJsonElement(attestation).jsonObject
    }
    return JsonObject(Json.encodeToJsonElement(metadata).jsonObject + attestation)
}

@Serializable
data class Jwks(val keys: List<Jwk>)
