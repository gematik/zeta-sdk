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
package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildConfigTest {
    private fun buildConfig(productId: String = "ZETA-Test-Client", productVersion: String = "1.3.2") = BuildConfig(
        productId = productId,
        productVersion = productVersion,
        clientName = "client",
        storageConfig = StorageConfig.Custom(InMemoryStorage()),
        tpmConfig = object : TpmConfig {},
        authConfig = AuthConfig(listOf("scope"), 300, true, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")), requiredRoleOid = ""),
        platformProductId = PlatformProductId.LinuxProductId("", "", "", ""),
    )

    @Test
    fun acceptsProductIdAndVersion_withinA25338Limits() {
        val config = buildConfig(productId = "Abc-123-" + "x".repeat(12), productVersion = "1.0.0-rc.1-" + "9".repeat(9))

        assertEquals(20, config.productId.length)
        assertEquals(20, config.productVersion.length)
    }

    @Test
    fun rejectsProductId_longerThan20Characters() {
        assertFailsWith<IllegalArgumentException> { buildConfig(productId = "x".repeat(21)) }
    }

    @Test
    fun rejectsProductId_empty() {
        assertFailsWith<IllegalArgumentException> { buildConfig(productId = "") }
    }

    @Test
    fun rejectsProductId_withCharactersOutsideTheAllowedSet() {
        listOf("demo_client", "demo client", "1.3", "Prüf-Client").forEach { productId ->
            assertFailsWith<IllegalArgumentException>(productId) { buildConfig(productId = productId) }
        }
    }

    @Test
    fun rejectsProductVersion_emptyOrLongerThan20Characters() {
        assertFailsWith<IllegalArgumentException> { buildConfig(productVersion = "") }
        assertFailsWith<IllegalArgumentException> { buildConfig(productVersion = "1".repeat(21)) }
    }

    @Test
    fun rejectsProductVersion_withCharactersOutsideTheAllowedSet() {
        listOf("1.3.2 beta", "1.3.2_1", "1.3.2+build").forEach { productVersion ->
            assertFailsWith<IllegalArgumentException>(productVersion) { buildConfig(productVersion = productVersion) }
        }
    }

    @Test
    fun copy_validatesTheNewValues() {
        assertFailsWith<IllegalArgumentException> { buildConfig().copy(productId = "bad_id") }
    }
}
