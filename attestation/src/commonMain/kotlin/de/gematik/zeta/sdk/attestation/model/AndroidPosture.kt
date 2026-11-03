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

@Serializable
data class AndroidPosture(
    @SerialName("platform_product_id") val platformProductId: PlatformProductId,
    @SerialName("product_id") val productId: String,
    @SerialName("product_version") val productVersion: String,
    @SerialName("build") val build: AndroidBuild,
    @SerialName("ro") val ro: AndroidRo? = null,
    @SerialName("packageManager") val packageManager: AndroidPackageManagerInfo? = null,
    @SerialName("keyguardManager") val keyguardManager: AndroidKeyguardManagerInfo? = null,
    @SerialName("biometricManager") val biometricManager: AndroidBiometricManagerInfo? = null,
    @SerialName("devicePolicyManager") val devicePolicyManager: AndroidDevicePolicyManagerInfo? = null,
    @SerialName("nonce") val nonce: String,
    @SerialName("signed_client_data_hash") val signedClientDataHash: String? = null,
    @SerialName("play_integrity_token") val playIntegrityToken: String? = null,
)

@Serializable
data class AndroidBuild(
    @SerialName("version") val version: AndroidBuildVersion,
    @SerialName("manufacturer") val manufacturer: String,
    @SerialName("product") val product: String,
    @SerialName("model") val model: String,
    @SerialName("board") val board: String,
)

@Serializable
data class AndroidBuildVersion(
    @SerialName("sdk_int") val sdkInt: Int,
    @SerialName("security_patch") val securityPatch: String,
)

@Serializable
data class AndroidRo(
    @SerialName("crypto") val crypto: AndroidRoCrypto? = null,
    @SerialName("product") val product: AndroidRoProduct? = null,
)

@Serializable
data class AndroidRoCrypto(@SerialName("state") val state: Boolean)

@Serializable
data class AndroidRoProduct(@SerialName("first_api_level") val firstApiLevel: Int)

@Serializable
data class AndroidPackageManagerInfo(
    @SerialName("feature_verified_boot") val featureVerifiedBoot: Boolean? = null,
    @SerialName("mainline_patch_level") val mainlinePatchLevel: String? = null,
)

@Serializable
data class AndroidKeyguardManagerInfo(@SerialName("isDeviceSecure") val isDeviceSecure: Boolean)

@Serializable
data class AndroidBiometricManagerInfo(
    @SerialName("deviceCredential") val deviceCredential: Boolean? = null,
    @SerialName("biometricStrong") val biometricStrong: Boolean? = null,
)

@Serializable
data class AndroidDevicePolicyManagerInfo(@SerialName("passwordComplexity") val passwordComplexity: Int? = null)
