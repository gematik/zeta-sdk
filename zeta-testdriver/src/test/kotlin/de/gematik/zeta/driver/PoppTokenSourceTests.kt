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

package de.gematik.zeta.driver

import de.gematik.zeta.driver.core.popp.PoppActor
import de.gematik.zeta.driver.core.popp.PoppGeneratorConfig
import de.gematik.zeta.driver.core.popp.PoppTokenProvider
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.sdk.authentication.AuthMode
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PoppTokenSourceTests {

    private val actor = PoppActor("1-actor", "1.2.3")

    @Test
    fun generatedTokenFor_returnsNull_withoutRequest_whenAuthModeOidc() = runTest {
        // Arrange
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}")
        }
        val source = GeneratorPoppTokenSource(PoppTokenProvider(ZetaHttpClient(HttpClient(engine))))
        val config = SdkInstanceConfig(
            authMode = AuthMode.OIDC,
            poppTokenGeneratorUrl = "https://gen",
            proofMethod = "m",
            patientId = "P",
            insurerId = "I",
            smbKeystoreB64 = Base64.encode("p12".encodeToByteArray()),
        )

        // Act
        val result = source.generatedTokenFor(config)

        // Assert
        assertNull(result)
        assertEquals(0, requests)
    }

    @Test
    fun poppGeneratorConfig_mapsConfigFields() {
        // Arrange
        val config = SdkInstanceConfig(
            poppTokenGeneratorUrl = "https://gen",
            proofMethod = "ehc-practitioner-user-x509",
            patientId = "X110639491",
            insurerId = "109500969",
        )

        // Act
        val result = config.poppGeneratorConfig()

        // Assert
        assertEquals(PoppGeneratorConfig("https://gen", "ehc-practitioner-user-x509", "X110639491", "109500969"), result)
    }

    @Test
    fun readPoppActor_usesKeystoreB64_whenBothSet() {
        // Arrange
        val config = SdkInstanceConfig(
            smbKeystoreB64 = Base64.encode("p12".encodeToByteArray()),
            smbKeystoreFile = "/x.p12",
            smbKeystoreAlias = "alias",
            smbKeystorePassword = "pw",
        )
        var bytes: String? = null

        // Act
        val result = config.readPoppActor(
            fromBytes = { b, a, p ->
                bytes = "${b.decodeToString()}|$a|$p"
                actor
            },
            fromFile = { _, _, _ -> error("file must not be used") },
        )

        // Assert
        assertEquals(actor, result)
        assertEquals("p12|alias|pw", bytes)
    }

    @Test
    fun readPoppActor_usesKeystoreFile_whenB64Empty() {
        // Arrange
        val config = SdkInstanceConfig(smbKeystoreFile = "/x.p12", smbKeystoreAlias = "alias", smbKeystorePassword = "pw")
        var args: String? = null

        // Act
        val result = config.readPoppActor(
            fromBytes = { _, _, _ -> error("bytes must not be used") },
            fromFile = { f, a, p ->
                args = "$f|$a|$p"
                actor
            },
        )

        // Assert
        assertEquals(actor, result)
        assertEquals("/x.p12|alias|pw", args)
    }

    @Test
    fun readPoppActor_throws_whenNoKeystore() {
        // Arrange
        val config = SdkInstanceConfig()

        // Act + Assert
        assertFailsWith<IllegalStateException> { config.readPoppActor() }
    }

    @Test
    fun withPoppToken_overridesStaticToken() {
        // Arrange
        val config = SdkInstanceConfig(poppToken = "static")

        // Act + Assert
        assertEquals("gen", config.withPoppToken("gen").poppToken)
        assertEquals("static", config.withPoppToken(null).poppToken)
    }
}
