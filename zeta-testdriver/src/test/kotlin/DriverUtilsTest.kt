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

import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.driver.newSdk
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.storage.InMemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class DriverUtilsTest {
    @Test
    fun newSdk_usesCorrectPlatformProductId() {
        // Arrange
        val config = SdkInstanceConfig(
            fachdienstUrl = "https://example.com",
            smbKeystoreB64 = "dGVzdA==",
            smbKeystoreAlias = "alias",
            smbKeystorePassword = "password",
            smbKeystoreFile = "",
            smcbBaseUrl = "",
            aslProdEnv = false,
            poppToken = "",
        )

        // Act
        val client = newSdk(InMemoryStorage(), config)

        val buildConfig = client::class.java
            .getDeclaredField("cfg")
            .apply { isAccessible = true }
            .get(client)

        val platformProductId = buildConfig::class.java
            .getDeclaredField("platformProductId")
            .apply { isAccessible = true }
            .get(buildConfig) as PlatformProductId

        val osName = System.getProperty("os.name").lowercase()
        when {
            osName.contains("linux") -> {
                assertIs<PlatformProductId.LinuxProductId>(platformProductId)
                assertEquals("linux", platformProductId.platform)
            }

            osName.contains("mac") || osName.contains("darwin") -> {
                assertIs<PlatformProductId.AppleProductId>(platformProductId)
                assertEquals("apple", platformProductId.platform)
            }

            osName.contains("win") -> {
                assertIs<PlatformProductId.WindowsProductId>(platformProductId)
                assertEquals("windows", platformProductId.platform)
            }

            else -> fail("Unsupported platform: $osName")
        }
    }
}
