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

package de.gematik.zeta.sdk.attestation.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SoftwarePosture(
    @SerialName("platform_product_id") val platformProductId: PlatformProductId? = null,
    @SerialName("product_id") val productId: String,
    @SerialName("product_version")val productVersion: String,
    /* Operating system name*/
    val os: String,
    /* Operating system version */
    @SerialName("os_version") val osVersion: String,
    /* Hardware Architecture */
    val arch: String,
    /* The public self signed signing key (PEM or base64 DER encoded) */
    @SerialName("public_key") val publicKey: String,
    /* The attestation challenge of the client instance, used to verify the public client instance key and the nonce from AS. */
    @SerialName("attestation_challenge") val attestationChallenge: String,
)

@Serializable
data class TpmPosture(
    @SerialName("platform_product_id") val platformProductId: PlatformProductId,
    @SerialName("product_id") val productId: String,
    @SerialName("product_version")val productVersion: String,
    val os: String,
    @SerialName("os_version") val osVersion: String,
    val arch: String,
    @SerialName("tpm_attestation_key") val tpmAttestationKey: String,
    @SerialName("tpm_quote") val tpmQuote: String,
    // @SerialName("tpm_quote_signature") val tpmQuoteSignature: String,
    @SerialName("tpm_event_log") val tpmEventLog: String,
    @SerialName("tpm_ek_certificate_chain") val tpmEkCertificateChain: List<String>,
)

@Serializable
data class ApplePosture(
    @SerialName("platform_product_id") val platformProductId: PlatformProductId,
    @SerialName("product_id") val productId: String,
    @SerialName("product_version")val productVersion: String,
    @SerialName("system_version") val systemVersion: String,
    @SerialName("system_name") val systemName: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("key_id") val keyId: String,
    @SerialName("fmt") val fmt: String = APPLE_APP_ATTEST_FMT,
    @SerialName("attStmt") val attStmt: AppleAttestationStatement,
    @SerialName("authData") val authData: AppleAttestationAuthData,
    @SerialName("signature") val signature: String? = null,
    @SerialName("assertionAuthenticatorData") val assertionAuthenticatorData: AssertionAuthenticatorData? = null,
)

const val APPLE_APP_ATTEST_FMT = "apple-appattest"

@Serializable
data class AppleAttestationStatement(
    @SerialName("x5c") val x5c: List<String>,
    @SerialName("receipt") val receipt: String,
)

@Serializable
data class AppleAttestationAuthData(
    @SerialName("rpidHash") val rpidHash: String,
    @SerialName("flags") val flags: String,
    @SerialName("counter") val counter: Long,
    @SerialName("aaguid") val aaguid: String,
    @SerialName("credentialId") val credentialId: String,
)

@Serializable
data class AssertionAuthenticatorData(
    @SerialName("rpidHash")
    val rpidHash: String,
    @SerialName("counter")
    val counter: Long,
)

expect suspend fun buildPosture(platformProductId: PlatformProductId?, productId: String, productVersion: String, attChallenge: String, publicKeyB64: String): JsonElement
expect suspend fun getPlatform(): Platform
expect fun getPostureType(): PostureType
