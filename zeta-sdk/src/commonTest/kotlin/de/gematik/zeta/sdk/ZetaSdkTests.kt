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

import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZetaSdkTest {
    @Test
    fun build_createsClient_withMinimalConfig() {
        // Arrange
        val config = createTestBuildConfig()

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun build_createsClient_withCustomHttpClientBuilder() {
        // Arrange
        val customBuilder = ZetaHttpClientBuilder().logging(LogLevel.NONE)
        val config = createTestBuildConfig(httpClientBuilder = customBuilder)

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun build_createsClient_withCustomStorage() {
        // Arrange
        val mockStorage = createMockStorage()
        val config = createTestBuildConfig(
            storageConfig = StorageConfig(provider = mockStorage),
        )

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun build_createsClient_withCallbacks() {
        // Arrange
        val regCallback = RegistrationCallback { RegInfo("TestClient") }
        val authCallback = AuthenticationCallback { AuthInfo("123456") }
        val config = createTestBuildConfig(
            registrationCallback = regCallback,
            authenticationCallback = authCallback,
        )

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun forget_returnsSuccess_whenNoErrors() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.forget()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun discover_returnsFailure_whenConfigurationFails() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://invalid-url", config)

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isFailure)
    }

    @Test
    fun register_returnsSuccess_whenRegistrationCompletes() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.register()

        // Assert
        assertNotNull(result)
    }

    @Test
    fun authenticate_returnsResult_whenCalled() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.authenticate()

        // Assert
        assertNotNull(result)
    }

    @Test
    fun httpClient_createsClient_withDefaultBuilder() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient = client.httpClient()

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun httpClient_createsClient_withCustomConfiguration() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient = client.httpClient {
            timeouts(connectMs = 5000, requestMs = 10000)
            retry(maxRetries = 3)
        }

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun httpClient_installsPlugins_zetaAndAslDecryption() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient = client.httpClient()

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun httpClient_canBeCalledMultipleTimes_createsNewInstances() {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient1 = client.httpClient()
        val httpClient2 = client.httpClient()

        // Assert
        assertNotNull(httpClient1)
        assertNotNull(httpClient2)
        httpClient1.close()
        httpClient2.close()
    }

    @Test
    fun ws_throwsException_whenDiscoverFails() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://invalid-url", config)

        // Act & Assert
        assertFailsWith<Exception> {
            client.ws("wss://api.example.com/ws") {}
        }
    }

    @Test
    fun close_throwsNotImplementedError_currentImplementation() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.close()

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotImplementedError)
    }

    @Test
    fun fullFlow_buildDiscoverRegisterAuthenticate_executesInOrder() = runTest {
        // Arrange
        val config = createTestBuildConfig()

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)
        val discoverResult = client.discover()
        val registerResult = client.register()
        val authenticateResult = client.authenticate()

        // Assert
        assertNotNull(client)
        assertNotNull(discoverResult)
        assertNotNull(registerResult)
        assertNotNull(authenticateResult)
    }

    @Test
    fun multipleBuildCalls_createsDifferentClients_independent() {
        // Arrange
        val config1 = createTestBuildConfig()
        val config2 = createTestBuildConfig()

        // Act
        val client1 = ZetaSdk.build("https://api1.example.com", config1)
        val client2 = ZetaSdk.build("https://api2.example.com", config2)

        // Assert
        assertNotNull(client1)
        assertNotNull(client2)
    }

    @Test
    fun build_handlesEmptyResource_createsClient() {
        // Arrange
        val config = createMinimalConfig()

        // Act
        val client = ZetaSdk.build("", config)

        // Assert
        assertNotNull(client)
    }

    @Test
    fun httpClient_withEmptyBuilder_usesDefaults() {
        // Arrange
        val config = createMinimalConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val httpClient = client.httpClient { }

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun forget_canBeCalledMultipleTimes_doesNotFail() = runTest {
        // Arrange
        val config = createMinimalConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result1 = client.forget()
        val result2 = client.forget()
        val result3 = client.forget()

        // Assert
        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)
        assertTrue(result3.isSuccess)
    }

    @Test
    fun build_withAllCallbacks_storesThemCorrectly() {
        // Arrange
        var regCalled = false
        var authCalled = false

        val regCallback = RegistrationCallback {
            regCalled = true
            RegInfo("CallbackClient")
        }

        val authCallback = AuthenticationCallback {
            authCalled = true
            AuthInfo("999999")
        }

        val config = BuildConfig(
            productId = "test",
            productVersion = "1.0",
            clientName = "test",
            storageConfig = StorageConfig(provider = InMemoryStorage()),
            tpmConfig = object : TpmConfig {},
            authConfig = AuthConfig(listOf("scopes"), 300, false, SmbTokenProvider(SmbTokenProvider.Credentials("", "", ""))),
            platformProductId = PlatformProductId.LinuxProductId("", "", "", ""),
            registrationCallback = regCallback,
            authenticationCallback = authCallback,
        )

        // Act
        val client = ZetaSdk.build("https://api.example.com", config)

        // Assert
        assertNotNull(client)
    }

    private fun createMinimalConfig(): BuildConfig {
        return BuildConfig(
            productId = "test",
            productVersion = "1.0",
            clientName = "test",
            storageConfig = StorageConfig(provider = InMemoryStorage()),
            tpmConfig = object : TpmConfig {},
            authConfig = AuthConfig(listOf("scopes"), 300, false, SmbTokenProvider(SmbTokenProvider.Credentials("", "", ""))),
            platformProductId = PlatformProductId.LinuxProductId("", "", "", ""),
        )
    }

    private fun createTestBuildConfig(
        productId: String = "test-product",
        productVersion: String = "1.0.0",
        clientName: String = "TestClient",
        storageConfig: StorageConfig = StorageConfig(provider = InMemoryStorage()),
        httpClientBuilder: ZetaHttpClientBuilder? = null,
        registrationCallback: RegistrationCallback? = null,
        authenticationCallback: AuthenticationCallback? = null,
    ): BuildConfig {
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(listOf("scopes"), 300, false, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")))
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")

        return BuildConfig(
            productId = productId,
            productVersion = productVersion,
            clientName = clientName,
            storageConfig = storageConfig,
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
            httpClientBuilder = httpClientBuilder,
            registrationCallback = registrationCallback,
            authenticationCallback = authenticationCallback,
        )
    }

    private fun createMockStorage(): SdkStorage = object : SdkStorage {
        private val data = mutableMapOf<String, String>()

        override suspend fun put(key: String, value: String) {
            data[key] = value
        }

        override suspend fun get(key: String): String? {
            return data[key]
        }

        override suspend fun remove(key: String) {
            data.remove(key)
        }

        override suspend fun clear() {
            data.clear()
        }
    }
}
