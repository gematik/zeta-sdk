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

import de.gematik.zeta.driver.core.buildForwardHeaders
import de.gematik.zeta.driver.core.shouldForwardHeader
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.storage.InMemoryStorage
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
            aslProdEnv = false,
            poppToken = "",
            requiredOid = "",
        )

        val client = newSdk(InMemoryStorage(), config)
        val buildConfig = client::class.java
            .getDeclaredField("cfg")
            .apply { isAccessible = true }
            .get(client)

        val platformProductId = buildConfig::class.java
            .getDeclaredField("platformProductId")
            .apply { isAccessible = true }
            .get(buildConfig) as PlatformProductId

        // Assert
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

    @Test
    fun newSdk_usesSmbTokenProvider_whenSmbKeystoreB64Provided() {
        // Arrange
        val config = SdkInstanceConfig(
            fachdienstUrl = "https://example.com",
            smbKeystoreB64 = "dGVzdA==",
            smbKeystoreAlias = "alias",
            smbKeystorePassword = "password",
            smbKeystoreFile = "",
            aslProdEnv = false,
            poppToken = "",
            requiredOid = "",
        )

        // Act
        val client = newSdk(InMemoryStorage(), config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun newSdk_usesSmbTokenProvider_whenSmbKeystoreFileProvided() {
        // Arrange
        val config = SdkInstanceConfig(
            fachdienstUrl = "https://example.com",
            smbKeystoreB64 = "",
            smbKeystoreAlias = "alias",
            smbKeystorePassword = "password",
            smbKeystoreFile = "/path/to/keystore.p12",
            aslProdEnv = false,
            poppToken = "",
            requiredOid = "",
        )

        // Act
        val client = newSdk(InMemoryStorage(), config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun newSdk_throwsError_whenNoAuthConfigProvided() {
        // Arrange
        val config = SdkInstanceConfig(
            fachdienstUrl = "https://example.com",
            smbKeystoreB64 = "",
            smbKeystoreAlias = "",
            smbKeystorePassword = "",
            smbKeystoreFile = "",
            aslProdEnv = false,
            poppToken = "",
            requiredOid = "",
        )

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            newSdk(InMemoryStorage(), config)
        }
    }

    @Test
    fun newSdk_throwsError_whenFachdienstUrlIsNull() {
        // Arrange
        val config = SdkInstanceConfig(
            fachdienstUrl = null,
            smbKeystoreB64 = "dGVzdA==",
            smbKeystoreAlias = "alias",
            smbKeystorePassword = "password",
            smbKeystoreFile = "",
            aslProdEnv = false,
            poppToken = "",
            requiredOid = "",
        )

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            newSdk(InMemoryStorage(), config)
        }
    }

    @Test
    fun shouldAutoForwardHeader_returnsTrue_whenHeaderIsAllowed() {
        // Assert
        assertTrue(shouldForwardHeader("Authorization", true))
        assertTrue(shouldForwardHeader("X-Custom-Header", true))
        assertTrue(shouldForwardHeader("Accept", true))
        assertTrue(shouldForwardHeader("Authorization", false))
        assertTrue(shouldForwardHeader("X-Custom-Header", false))
        assertTrue(shouldForwardHeader("Accept", false))
    }

    @Test
    fun shouldAutoForwardHeader_forwardHostHeaders() {
        // Assert
        assertTrue(shouldForwardHeader("Host", false))
        assertTrue(shouldForwardHeader("Forwarded", false))
        assertTrue(shouldForwardHeader("X-Forwarded-Host", false))
        assertTrue(shouldForwardHeader("X-Forwarded-Port", false))
    }

    @Test
    fun shouldAutoForwardHeader_blockHostHeaders() {
        // Assert
        assertFalse(shouldForwardHeader("Host", true))
        assertFalse(shouldForwardHeader("Forwarded", true))
        assertFalse(shouldForwardHeader("X-Forwarded-Host", true))
        assertFalse(shouldForwardHeader("X-Forwarded-Port", true))
    }

    @Test
    fun shouldAutoForwardHeader_hostHeadersIsCaseInsensitive() {
        // Assert
        assertFalse(shouldForwardHeader("x-forwarded-host", true))
        assertFalse(shouldForwardHeader("X-Forwarded-Host", true))
        assertFalse(shouldForwardHeader("X-FORWARDED-HOST", true))
    }

    @Test
    fun shouldAutoForwardHeader_returnsFalse_whenHeaderIsContentType() {
        // Assert
        assertFalse(shouldForwardHeader(HttpHeaders.ContentType, true))
        assertFalse(shouldForwardHeader(HttpHeaders.ContentType, false))
    }

    @Test
    fun shouldAutoForwardHeader_returnsFalse_whenHeaderIsContentLength() {
        // Assert
        assertFalse(shouldForwardHeader(HttpHeaders.ContentLength, true))
        assertFalse(shouldForwardHeader(HttpHeaders.ContentLength, false))
    }

    @Test
    fun shouldAutoForwardHeader_returnsFalse_whenHeaderIsTransferEncoding() {
        // Assert
        assertFalse(shouldForwardHeader(HttpHeaders.TransferEncoding, true))
        assertFalse(shouldForwardHeader(HttpHeaders.TransferEncoding, false))
    }

    @Test
    fun shouldAutoForwardHeader_returnsFalse_whenHeaderIsConnection() {
        // Assert
        assertFalse(shouldForwardHeader(HttpHeaders.Connection, true))
        assertFalse(shouldForwardHeader(HttpHeaders.Connection, false))
    }

    @Test
    fun shouldAutoForwardHeader_isCaseInsensitive() {
        // Assert
        assertFalse(shouldForwardHeader("content-type", false))
        assertFalse(shouldForwardHeader("CONTENT-TYPE", false))
        assertFalse(shouldForwardHeader("Content-Type", false))
    }

    @Test
    fun buildForwardHeaders_doesNotDuplicatePoPP_whenAlreadyPresentInIncomingRequest() {
        // Arrange
        val incoming = Headers.build { append(POPP_TOKEN_HEADER_NAME, "incoming-token") }

        // Act
        val result = buildForwardHeaders(incoming, poppToken = "config-token", false)

        // Assert
        assertEquals(1, result.getAll(POPP_TOKEN_HEADER_NAME)?.size)
        assertEquals("incoming-token", result[POPP_TOKEN_HEADER_NAME])
    }

    @Test
    fun buildForwardHeaders_usesFallbackPoppToken_whenNotPresentInIncomingRequest() {
        // Arrange
        val incoming = Headers.build { append("Accept", "*/*") }

        // Act
        val result = buildForwardHeaders(incoming, poppToken = "config-token", false)

        // Assert
        assertEquals("config-token", result[POPP_TOKEN_HEADER_NAME])
    }

    @Test
    fun buildForwardHeaders_doesNotSetPoPP_whenNotInIncomingAndPoppTokenIsNull() {
        // Arrange
        val incoming = Headers.build { append("Accept", "*/*") }

        // Act
        val result = buildForwardHeaders(incoming, poppToken = null, false)

        // Assert
        assertNull(result[POPP_TOKEN_HEADER_NAME])
    }
}
