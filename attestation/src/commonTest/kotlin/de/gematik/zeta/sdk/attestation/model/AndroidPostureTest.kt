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

import de.gematik.zeta.sdk.attestation.assertion.hardwarePostureJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AndroidPostureTest {
    private val base = AndroidPosture(
        platformProductId = PlatformProductId.AndroidProductId(packageName = "de.gematik.app", sha256CertFingerprints = listOf("AA:BB")),
        productId = "demo",
        productVersion = "1.0",
        build = AndroidBuild(AndroidBuildVersion(sdkInt = 34, securityPatch = "2026-08-05"), "Google", "husky", "Pixel 8 Pro", "husky"),
        keyguardManager = AndroidKeyguardManagerInfo(isDeviceSecure = true),
        nonce = "CQgH",
    )
    private val full = base.copy(
        ro = AndroidRo(AndroidRoCrypto(state = true), AndroidRoProduct(firstApiLevel = 33)),
        packageManager = AndroidPackageManagerInfo(featureVerifiedBoot = true, mainlinePatchLevel = "2026-08-01"),
        biometricManager = AndroidBiometricManagerInfo(deviceCredential = true, biometricStrong = false),
        devicePolicyManager = AndroidDevicePolicyManagerInfo(passwordComplexity = 3),
        signedClientDataHash = "c2ln",
        playIntegrityToken = "pit",
    )

    private fun encode(posture: AndroidPosture) = hardwarePostureJson.encodeToJsonElement(AndroidPosture.serializer(), posture)

    @Test
    fun serializesTab13FieldNames() {
        val json = encode(base).jsonObject
        assertEquals("demo", json["product_id"]!!.jsonPrimitive.content)
        assertEquals("34", json["build"]!!.jsonObject["version"]!!.jsonObject["sdk_int"]!!.jsonPrimitive.content)
        assertEquals("2026-08-05", json["build"]!!.jsonObject["version"]!!.jsonObject["security_patch"]!!.jsonPrimitive.content)
        assertEquals("true", json["keyguardManager"]!!.jsonObject["isDeviceSecure"]!!.jsonPrimitive.content)
        assertEquals("android_app", json["platform_product_id"]!!.jsonObject["namespace"]!!.jsonPrimitive.content)
        assertEquals("CQgH", json["nonce"]!!.jsonPrimitive.content)
    }

    @Test
    fun omitsAbsentFields_andSignatureUntilSet() {
        val json = hardwarePostureJson.encodeToJsonElement(AndroidPosture.serializer(), base).jsonObject
        assertFalse("ro" in json)
        assertFalse("biometricManager" in json)
        assertFalse("signed_client_data_hash" in json)

        val signed = hardwarePostureJson.encodeToJsonElement(AndroidPosture.serializer(), base.copy(signedClientDataHash = "c2ln")).jsonObject
        assertEquals("c2ln", signed["signed_client_data_hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun serializesPlayIntegrityToken_onlyWhenSet() {
        val without = hardwarePostureJson.encodeToJsonElement(AndroidPosture.serializer(), base).jsonObject
        assertFalse("play_integrity_token" in without)

        val with = hardwarePostureJson.encodeToJsonElement(AndroidPosture.serializer(), base.copy(playIntegrityToken = "pit")).jsonObject
        assertEquals("pit", with["play_integrity_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun serializesNestedTab13FieldNames() {
        val json = encode(full).jsonObject
        assertEquals("true", json["ro"]!!.jsonObject["crypto"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("33", json["ro"]!!.jsonObject["product"]!!.jsonObject["first_api_level"]!!.jsonPrimitive.content)
        assertEquals("true", json["packageManager"]!!.jsonObject["feature_verified_boot"]!!.jsonPrimitive.content)
        assertEquals("2026-08-01", json["packageManager"]!!.jsonObject["mainline_patch_level"]!!.jsonPrimitive.content)
        assertEquals("true", json["biometricManager"]!!.jsonObject["deviceCredential"]!!.jsonPrimitive.content)
        assertEquals("false", json["biometricManager"]!!.jsonObject["biometricStrong"]!!.jsonPrimitive.content)
        assertEquals("3", json["devicePolicyManager"]!!.jsonObject["passwordComplexity"]!!.jsonPrimitive.content)
    }
}
