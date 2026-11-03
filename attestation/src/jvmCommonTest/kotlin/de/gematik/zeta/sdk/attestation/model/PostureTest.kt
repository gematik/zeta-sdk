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

import de.gematik.zeta.platform.PlatformInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class PostureTest {
    // In Phase 1 (Stufe 1) no hardware attestation is included.
    // Therefore, even for Apple devices, a Software Posture is returned.
    @Test
    fun buildPostureInternal_returnsSoftwarePosture_whenPlatformIsApple() {
        val result = buildPostureInternal(
            platform = Platform.APPLE,
            platformInfo = PlatformInfo(
                os = "macOS",
                osVersion = "14.5",
                arch = "arm64",
            ),
            productId = "product-id",
            productVersion = "1.2.3",
            attChallenge = "challenge-123",
            publicKeyB64 = "public-key-abc",
        ).jsonObject

        requireNotNull(result)
        assertEquals("product-id", result.string("product_id"))
        assertEquals("1.2.3", result.string("product_version"))
        assertEquals("macOS", result.string("os"))
        assertEquals("14.5", result.string("os_version"))
        assertEquals("public-key-abc", result.string("public_key"))
        assertEquals("challenge-123", result.string("attestation_challenge"))

        // currently not supported on apple & Software Attestation (see ZETAP-1103)
        assertFalse { result.containsKey("platform_product_id") }
    }

    @Test
    fun buildPostureInternal_returnsSoftwarePosture_whenPlatformIsLinux() {
        val result = buildPostureInternal(
            platform = Platform.LINUX,
            platformInfo = PlatformInfo(
                os = "linux",
                osVersion = "6.8.0",
                arch = "x86_64",
            ),
            platformProductId = PlatformProductId.LinuxProductId("linux", "", "", ""),
            productId = "product-id",
            productVersion = "1.2.3",
            attChallenge = "challenge-123",
            publicKeyB64 = "public-key-abc",
        ).jsonObject

        assertEquals("product-id", result.string("product_id"))
        assertEquals("1.2.3", result.string("product_version"))
        assertEquals("linux", result.string("os"))
        assertEquals("6.8.0", result.string("os_version"))
        assertEquals("x86_64", result.string("arch"))
        assertEquals("public-key-abc", result.string("public_key"))
        assertEquals("challenge-123", result.string("attestation_challenge"))

        assertTrue { result.containsKey("platform_product_id") }
        val platformProductIdJson: JsonElement? = result.get("platform_product_id")
        if (platformProductIdJson != null) {
            val platformProductId = platformProductIdJson.jsonObject
            assertEquals("linux", platformProductId.string("platform"))
        }
    }

    @Test
    fun buildPostureInternal_returnsSoftwarePosture_whenPlatformIsWindows() {
        val result = buildPostureInternal(
            platform = Platform.WINDOWS,
            platformInfo = PlatformInfo(
                os = "windows",
                osVersion = "11",
                arch = "amd64",
            ),
            platformProductId = PlatformProductId.WindowsProductId("windows", "", ""),
            productId = "windows",
            productVersion = "1.2.3",
            attChallenge = "challenge-123",
            publicKeyB64 = "public-key-abc",
        ).jsonObject

        assertEquals("windows", result.string("os"))
        assertEquals("11", result.string("os_version"))
        assertEquals("amd64", result.string("arch"))
        assertEquals("public-key-abc", result.string("public_key"))
        assertEquals("challenge-123", result.string("attestation_challenge"))

        assertTrue { result.containsKey("platform_product_id") }
        val platformProductIdJson: JsonElement? = result.get("platform_product_id")
        if (platformProductIdJson != null) {
            val platformProductId = platformProductIdJson.jsonObject
            assertEquals("windows", platformProductId.string("platform"))
        }
    }

    @Test
    fun buildPostureInternal_returnsSoftwarePosture_whenPlatformIsAndroid() {
        val result = buildPostureInternal(
            platform = Platform.ANDROID,
            platformInfo = PlatformInfo(
                os = "android",
                osVersion = "15",
                arch = "arm64-v8a",
            ),
            platformProductId = PlatformProductId.AndroidProductId("android", "", "", emptyList()),
            productId = "android",
            productVersion = "1.2.3",
            attChallenge = "challenge-123",
            publicKeyB64 = "public-key-abc",
        ).jsonObject

        assertEquals("android", result.string("os"))
        assertEquals("15", result.string("os_version"))
        assertEquals("arm64-v8a", result.string("arch"))
        assertEquals("public-key-abc", result.string("public_key"))
        assertEquals("challenge-123", result.string("attestation_challenge"))
    }

    @Test
    fun mapPlatform_returnsApple_forMacos() {
        assertEquals(Platform.APPLE, mapPlatform(de.gematik.zeta.platform.Platform.Jvm.Macos))
    }

    @Test
    fun mapPlatform_returnsLinux_forLinux() {
        assertEquals(Platform.LINUX, mapPlatform(de.gematik.zeta.platform.Platform.Jvm.Linux))
    }

    @Test
    fun mapPlatform_returnsWindows_forWindows() {
        assertEquals(Platform.WINDOWS, mapPlatform(de.gematik.zeta.platform.Platform.Jvm.Windows))
    }

    @Test
    fun mapPlatform_returnsAndroid_forAndroid() {
        assertEquals(Platform.ANDROID, mapPlatform(de.gematik.zeta.platform.Platform.Android))
    }

    @Test
    fun mapPostureType_returnsApple_forMacos() {
        assertEquals(PostureType.SOFTWARE, mapPostureType(de.gematik.zeta.platform.Platform.Jvm.Macos))
    }

    @Test
    fun mapPostureType_returnsSoftware_forLinux() {
        assertEquals(PostureType.SOFTWARE, mapPostureType(de.gematik.zeta.platform.Platform.Jvm.Linux))
    }

    @Test
    fun mapPostureType_returnsSoftware_forWindows() {
        assertEquals(PostureType.SOFTWARE, mapPostureType(de.gematik.zeta.platform.Platform.Jvm.Windows))
    }

    @Test
    fun mapPostureType_returnsSoftware_forAndroid() {
        assertEquals(PostureType.SOFTWARE, mapPostureType(de.gematik.zeta.platform.Platform.Android))
    }

    @Test
    fun mapPlatform_andPostureType_throw_forNonJvmPlatform() {
        assertFailsWith<IllegalStateException> { mapPlatform(de.gematik.zeta.platform.Platform.IOS) }
        assertFailsWith<IllegalStateException> { mapPostureType(de.gematik.zeta.platform.Platform.IOS) }
    }
}

private fun JsonObject.string(key: String): String =
    requireNotNull(this[key]) { "Missing key '$key' in posture JSON" }.jsonPrimitive.content
