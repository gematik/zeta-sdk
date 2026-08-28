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
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorageImpl
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.configuration.ConfigurationStorageImpl
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
private const val requiredRoleOid = "1.2.276.0.76.4.261"

class ZetaSdkClientTests {
    private val aesTestKey: String = "7aae7xXr8rnzVqjpYbosS0CFMrlprkD7jbVotm0fd"
    private val resource = "https://fachdienst.example.com"
    private val scope = "scope"
    private val authServerIssuer = "https://auth.example.com"

    @Test
    fun storageConfig_createsWithDefaults_noParameters() {
        // Arrange & Act
        val config = StorageConfig.Default(aesB64Key = aesTestKey)

        // Assert
        assertEquals(aesTestKey, config.aesB64Key)
    }

    @Test
    fun storageConfig_createsWithProvider_whenProvided() {
        // Arrange
        val mockProvider = object : SdkStorage {
            override suspend fun put(key: String, value: String) {
                error("not in scope of the test")
            }

            override suspend fun get(key: String): String? {
                error("not in scope of the test")
            }

            override suspend fun remove(key: String) {
                error("not in scope of the test")
            }

            override suspend fun clear() {}
        }

        // Act
        val config = StorageConfig.Custom(provider = mockProvider)

        // Assert
        assertEquals(mockProvider, config.provider)
    }

    @Test
    fun storageConfig_createsWithCustomKey_whenProvided() {
        // Arrange
        val customKey = "customBase64Key=="

        // Act
        val config = StorageConfig.Default(aesB64Key = customKey)

        // Assert
        assertEquals(customKey, config.aesB64Key)
    }

    @Test
    fun storageConfig_equality_sameValues() {
        // Arrange
        val config1 = StorageConfig.Default(aesB64Key = "key1")
        val config2 = StorageConfig.Default(aesB64Key = "key1")

        // Act & Assert
        assertEquals(config1, config2)
    }

    @Test
    fun storageConfig_inequality_differentValues() {
        // Arrange
        val config1 = StorageConfig.Default(aesB64Key = "key1")
        val config2 = StorageConfig.Default(aesB64Key = "key2")

        // Act & Assert
        assertNotEquals(config1, config2)
    }

    @Test
    fun storageConfig_copy_createsNewInstance() {
        // Arrange
        val original = StorageConfig.Default(aesB64Key = "originalKey")

        // Act
        val copy = original.copy(aesB64Key = "newKey")

        // Assert
        assertEquals("originalKey", original.aesB64Key)
        assertEquals("newKey", copy.aesB64Key)
    }

    @Test
    fun storageConfig_componentN_destructuring() {
        // Arrange
        val mockProvider = object : SdkStorage {
            override suspend fun put(key: String, value: String) {
                error("not in scope of the test")
            }

            override suspend fun get(key: String): String? {
                error("not in scope of the test")
            }

            override suspend fun remove(key: String) {
                error("not in scope of the test")
            }

            override suspend fun clear() {}
        }
        val config = StorageConfig.Custom(provider = mockProvider)

        // Assert
        assertEquals(mockProvider, config.provider)
    }

    @Test
    fun regInfo_createsWithClientName_whenProvided() {
        // Arrange & Act
        val regInfo = RegInfo(clientName = "TestClient")

        // Assert
        assertEquals("TestClient", regInfo.clientName)
    }

    @Test
    fun regInfo_equality_sameClientName() {
        // Arrange
        val info1 = RegInfo(clientName = "Client1")
        val info2 = RegInfo(clientName = "Client1")

        // Act & Assert
        assertEquals(info1, info2)
    }

    @Test
    fun regInfo_inequality_differentClientName() {
        // Arrange
        val info1 = RegInfo(clientName = "Client1")
        val info2 = RegInfo(clientName = "Client2")

        // Act & Assert
        assertNotEquals(info1, info2)
    }

    @Test
    fun regInfo_copy_createsNewInstance() {
        // Arrange
        val original = RegInfo(clientName = "Original")

        // Act
        val copy = original.copy(clientName = "Copy")

        // Assert
        assertEquals("Original", original.clientName)
        assertEquals("Copy", copy.clientName)
    }

    @Test
    fun regInfo_component1_destructuring() {
        // Arrange
        val regInfo = RegInfo(clientName = "TestClient")

        // Act
        val (clientName) = regInfo

        // Assert
        assertEquals("TestClient", clientName)
    }

    @Test
    fun buildConfig_createsWithAllParameters_whenProvided() {
        // Arrange
        val storageConfig = StorageConfig.Default(aesB64Key = aesTestKey)
        val authConfig = AuthConfig(listOf("scopes"), 300, true, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")), requiredRoleOid = "1.2.276.0.76.4.261")
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")

        // Act
        val buildConfig = BuildConfig(
            productId = "product-123",
            productVersion = "1.0.0",
            clientName = "TestClient",
            storageConfig = storageConfig,
            tpmConfig = object : TpmConfig {},
            authConfig = authConfig,
            platformProductId = PlatformProductId.LinuxProductId("", "", "", ""),
        )

        // Assert
        assertEquals("product-123", buildConfig.productId)
        assertEquals("1.0.0", buildConfig.productVersion)
        assertEquals("TestClient", buildConfig.clientName)
        assertEquals(storageConfig, buildConfig.storageConfig)
        assertEquals(authConfig, buildConfig.authConfig)
        assertEquals(platformProductId, buildConfig.platformProductId)
        assertNull(buildConfig.httpClientBuilder)
        assertNull(buildConfig.registrationCallback)
    }

    @Test
    fun buildConfig_createsWithOptionalParameters_whenProvided() {
        // Arrange
        val storageConfig = StorageConfig.Default(aesB64Key = aesTestKey)
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(listOf("scopes"), 300, true, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")), requiredRoleOid = requiredRoleOid)
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")
        val httpClientBuilder = ZetaHttpClientBuilder()
        val regCallback = RegistrationCallback { RegInfo("test") }

        // Act
        val buildConfig = BuildConfig(
            productId = "product-123",
            productVersion = "1.0.0",
            clientName = "TestClient",
            storageConfig = storageConfig,
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
            httpClientBuilder = httpClientBuilder,
            registrationCallback = regCallback,
        )

        // Assert
        assertEquals(httpClientBuilder, buildConfig.httpClientBuilder)
        assertEquals(regCallback, buildConfig.registrationCallback)
    }

    @Test
    fun buildConfig_copy_createsNewInstance() {
        // Arrange
        val storageConfig = StorageConfig.Default(aesB64Key = aesTestKey)
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(listOf("scopes"), 300, true, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")), requiredRoleOid = requiredRoleOid)
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")
        val original = BuildConfig(
            productId = "product-123",
            productVersion = "1.0.0",
            clientName = "Original",
            storageConfig = storageConfig,
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
        )

        // Act
        val copy = original.copy(clientName = "Copy")

        // Assert
        assertEquals("Original", original.clientName)
        assertEquals("Copy", copy.clientName)
    }

    @Test
    fun buildConfig_equality_sameValues() {
        // Arrange
        val storageConfig = StorageConfig.Default(aesB64Key = aesTestKey)
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(listOf("scopes"), 300, true, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")), requiredRoleOid = requiredRoleOid)
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")

        val config1 = BuildConfig(
            productId = "product-123",
            productVersion = "1.0.0",
            clientName = "Client",
            storageConfig = storageConfig,
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
        )

        val config2 = BuildConfig(
            productId = "product-123",
            productVersion = "1.0.0",
            clientName = "Client",
            storageConfig = storageConfig,
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
        )

        // Act & Assert
        assertEquals(config1, config2)
    }

    @Test
    fun registrationCallback_invokesLambda_whenCalled() = runTest {
        // Arrange
        var invoked = false
        val callback = RegistrationCallback {
            invoked = true
            RegInfo("TestClient")
        }

        // Act
        val result = callback.registrationCb()

        // Assert
        assertTrue(invoked)
        assertEquals("TestClient", result.clientName)
    }

    @Test
    fun registrationCallback_returnsCorrectValue_whenInvoked() = runTest {
        // Arrange
        val expectedClientName = "ExpectedClient"
        val callback = RegistrationCallback {
            RegInfo(expectedClientName)
        }

        // Act
        val result = callback.registrationCb()

        // Assert
        assertEquals(expectedClientName, result.clientName)
    }

    @Test
    fun zetaSdkClient_canBeImplemented_mockImplementation() = runTest {
        // Arrange
        val mockClient = object : ZetaSdkClient {
            override suspend fun discover(): Result<Unit> = Result.success(Unit)
            override suspend fun register(): Result<Unit> = Result.success(Unit)
            override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
            override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
                return ZetaHttpClientBuilder().apply(builder).build()
            }
            override suspend fun <R> ws(
                targetUrl: String,
                builder: ZetaHttpClientBuilder.() -> Unit,
                customHeaders: Map<String, String>?,
                block: suspend DefaultClientWebSocketSession.() -> R,
            ) {}

            override suspend fun status(): Result<SdkStatus> {
                error("Not in scope of the test")
            }

            override suspend fun logout(): Result<Unit> = Result.success(Unit)
            override suspend fun close(): Result<Unit> = Result.success(Unit)
            override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
        }

        // Act
        val discoverResult = mockClient.discover()
        val registerResult = mockClient.register()
        val authenticateResult = mockClient.authenticate()
        val closeResult = mockClient.logout()

        // Assert
        assertTrue(discoverResult.isSuccess)
        assertTrue(registerResult.isSuccess)
        assertTrue(authenticateResult.isSuccess)
        assertTrue(closeResult.isSuccess)
    }

    @Test
    fun zetaSdkClient_httpClient_canBeInvoked() {
        // Arrange
        val mockClient = object : ZetaSdkClient {
            override suspend fun discover(): Result<Unit> = Result.success(Unit)
            override suspend fun register(): Result<Unit> = Result.success(Unit)
            override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
            override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
                return ZetaHttpClientBuilder().apply(builder).build()
            }
            override suspend fun <R> ws(
                targetUrl: String,
                builder: ZetaHttpClientBuilder.() -> Unit,
                customHeaders: Map<String, String>?,
                block: suspend DefaultClientWebSocketSession.() -> R,
            ) {}

            override suspend fun status(): Result<SdkStatus> {
                error("not in scope of the test")
            }

            override suspend fun logout(): Result<Unit> = Result.success(Unit)
            override suspend fun close(): Result<Unit> = Result.success(Unit)
            override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
        }

        // Act
        val httpClient = mockClient.httpClient {
            timeouts(connectMs = 5000)
        }

        // Assert
        assertNotNull(httpClient)
        httpClient.close()
    }

    @Test
    fun tpmConfig_canBeImplemented_emptyInterface() {
        // Arrange & Act
        val tpmConfig = object : TpmConfig {}

        // Assert
        assertNotNull(tpmConfig)
    }

    @Test
    fun tpmConfig_canBeUsedInBuildConfig_asParameter() {
        // Arrange
        val tpmConfig = object : TpmConfig {}
        val storageConfig = StorageConfig.Default(aesB64Key = aesTestKey)
        val authConfig = AuthConfig(listOf("scopes"), 300, true, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")), requiredRoleOid = requiredRoleOid)
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")

        // Act
        val buildConfig = BuildConfig(
            productId = "test",
            productVersion = "1.0",
            clientName = "test",
            storageConfig = storageConfig,
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
        )

        // Assert
        assertEquals(tpmConfig, buildConfig.tpmConfig)
    }

    @Test
    fun storageConfig_toString_containsEncryptionKey() {
        // Arrange
        val config = StorageConfig.Default(aesB64Key = "testKey123")

        // Act
        val result = config.toString()

        // Assert
        assertTrue(result.contains("testKey123"))
    }

    @Test
    fun storageConfig_hashCode_sameForEqualObjects() {
        // Arrange
        val config1 = StorageConfig.Default(aesB64Key = "sameKey")
        val config2 = StorageConfig.Default(aesB64Key = "sameKey")

        // Act & Assert
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun storageConfig_hashCode_differentForDifferentObjects() {
        // Arrange
        val config1 = StorageConfig.Default(aesB64Key = "key1")
        val config2 = StorageConfig.Default(aesB64Key = "key2")

        // Act & Assert
        assertNotEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun regInfo_toString_containsClientName() {
        // Arrange
        val regInfo = RegInfo(clientName = "MyClient")

        // Act
        val result = regInfo.toString()

        // Assert
        assertTrue(result.contains("MyClient"))
        assertTrue(result.contains("RegInfo"))
    }

    @Test
    fun regInfo_hashCode_sameForEqualObjects() {
        // Arrange
        val info1 = RegInfo(clientName = "SameName")
        val info2 = RegInfo(clientName = "SameName")

        // Act & Assert
        assertEquals(info1.hashCode(), info2.hashCode())
    }

    @Test
    fun regInfo_withEmptyClientName_createsValidObject() {
        // Arrange & Act
        val regInfo = RegInfo(clientName = "")

        // Assert
        assertEquals("", regInfo.clientName)
    }

    @Test
    fun regInfo_withLongClientName_createsValidObject() {
        // Arrange
        val longName = "A".repeat(1000)

        // Act
        val regInfo = RegInfo(clientName = longName)

        // Assert
        assertEquals(longName, regInfo.clientName)
    }

    @Test
    fun buildConfig_toString_containsMainFields() {
        // Arrange
        val config = createTestBuildConfig()

        // Act
        val result = config.toString()

        // Assert
        assertTrue(result.contains("product-123"))
        assertTrue(result.contains("1.0.0"))
        assertTrue(result.contains("TestClient"))
    }

    @Test
    fun buildConfig_withAllOptionalParameters_createsValidObject() {
        // Arrange
        val httpClientBuilder = ZetaHttpClientBuilder()
        val regCallback = RegistrationCallback { RegInfo("test") }

        // Act
        val config = BuildConfig(
            productId = "test",
            productVersion = "1.0",
            clientName = "test",
            storageConfig = StorageConfig.Default(aesB64Key = "7aae7xXr8rnzVqjpYbosS0CFMrlprkD7jbVotm0fd+w="),
            tpmConfig = object : TpmConfig {},
            authConfig = createTestAuthConfig(),
            platformProductId = createTestPlatformProductId(),
            httpClientBuilder = httpClientBuilder,
            registrationCallback = regCallback,
        )

        // Assert
        assertNotNull(config.httpClientBuilder)
        assertNotNull(config.registrationCallback)
    }

    @Test
    fun buildConfig_copyWithMultipleFields_updatesCorrectly() {
        // Arrange
        val original = createTestBuildConfig()

        // Act
        val copy = original.copy(
            productId = "new-product",
            productVersion = "2.0.0",
            clientName = "NewClient",
        )

        // Assert
        assertEquals("new-product", copy.productId)
        assertEquals("2.0.0", copy.productVersion)
        assertEquals("NewClient", copy.clientName)
        assertEquals("product-123", original.productId)
    }

    @Test
    fun buildConfig_inequality_differentProductId() {
        // Arrange
        val config1 = createTestBuildConfig()
        val config2 = config1.copy(productId = "different")

        // Act & Assert
        assertNotEquals(config1, config2)
    }

    @Test
    fun buildConfig_inequality_differentProductVersion() {
        // Arrange
        val config1 = createTestBuildConfig()
        val config2 = config1.copy(productVersion = "2.0.0")

        // Act & Assert
        assertNotEquals(config1, config2)
    }

    @Test
    fun buildConfig_withDifferentPlatformProductIds_createsValidConfigs() {
        // Arrange
        val baseConfig = createTestBuildConfig()

        val linuxConfig = baseConfig.copy(
            platformProductId = PlatformProductId.LinuxProductId("linux", "Ubuntu", "app", "1.0"),
        )

        val appleConfig = baseConfig.copy(
            platformProductId = PlatformProductId.AppleProductId("apple", "macOS", listOf("com.example")),
        )

        val windowsConfig = baseConfig.copy(
            platformProductId = PlatformProductId.WindowsProductId("windows", "11", "app"),
        )

        // Assert
        assertNotNull(linuxConfig.platformProductId)
        assertNotNull(appleConfig.platformProductId)
        assertNotNull(windowsConfig.platformProductId)
        assertTrue(linuxConfig.platformProductId is PlatformProductId.LinuxProductId)
        assertTrue(appleConfig.platformProductId is PlatformProductId.AppleProductId)
        assertTrue(windowsConfig.platformProductId is PlatformProductId.WindowsProductId)
    }

    @Test
    fun registrationCallback_canBeCalledMultipleTimes() = runTest {
        // Arrange
        var callCount = 0
        val callback = RegistrationCallback {
            callCount++
            RegInfo("Client-$callCount")
        }

        // Act
        val result1 = callback.registrationCb()
        val result2 = callback.registrationCb()

        // Assert
        assertEquals(2, callCount)
        assertEquals("Client-1", result1.clientName)
        assertEquals("Client-2", result2.clientName)
    }

    @Test
    fun registrationCallback_canAccessExternalState() = runTest {
        // Arrange
        var externalState = "Initial"
        val callback = RegistrationCallback {
            externalState = "Modified"
            RegInfo("TestClient")
        }

        // Act
        callback.registrationCb()

        // Assert
        assertEquals("Modified", externalState)
    }

    @Test
    fun zetaSdkClient_discover_canReturnFailure() = runTest {
        // Arrange
        val mockClient = object : ZetaSdkClient {
            override suspend fun discover(): Result<Unit> = Result.failure(Exception("Discovery failed"))
            override suspend fun register(): Result<Unit> = Result.success(Unit)
            override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
            override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
                return ZetaHttpClientBuilder().build()
            }
            override suspend fun <R> ws(
                targetUrl: String,
                builder: ZetaHttpClientBuilder.() -> Unit,
                customHeaders: Map<String, String>?,
                block: suspend DefaultClientWebSocketSession.() -> R,
            ) {}

            override suspend fun status(): Result<SdkStatus> {
                error("not in scope of the test")
            }

            override suspend fun logout(): Result<Unit> = Result.success(Unit)
            override suspend fun close(): Result<Unit> = Result.success(Unit)
            override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
        }

        // Act
        val result = mockClient.discover()

        // Assert
        assertTrue(result.isFailure)
        assertEquals("Discovery failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun zetaSdkClient_register_canReturnFailure() = runTest {
        // Arrange
        val mockClient = object : ZetaSdkClient {
            override suspend fun discover(): Result<Unit> = Result.success(Unit)
            override suspend fun register(): Result<Unit> = Result.failure(Exception("Registration failed"))
            override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
            override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
                return ZetaHttpClientBuilder().build()
            }
            override suspend fun <R> ws(
                targetUrl: String,
                builder: ZetaHttpClientBuilder.() -> Unit,
                customHeaders: Map<String, String>?,
                block: suspend DefaultClientWebSocketSession.() -> R,
            ) {}

            override suspend fun status(): Result<SdkStatus> {
                error("not in scope of the test")
            }

            override suspend fun logout(): Result<Unit> = Result.success(Unit)
            override suspend fun close(): Result<Unit> = Result.success(Unit)
            override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
        }

        // Act
        val result = mockClient.register()

        // Assert
        assertTrue(result.isFailure)
        assertEquals("Registration failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun zetaSdkClient_authenticate_canReturnFailure() = runTest {
        // Arrange
        val mockClient = object : ZetaSdkClient {
            override suspend fun discover(): Result<Unit> = Result.success(Unit)
            override suspend fun register(): Result<Unit> = Result.success(Unit)
            override suspend fun authenticate(): Result<Unit> = Result.failure(Exception("Auth failed"))
            override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
                return ZetaHttpClientBuilder().build()
            }
            override suspend fun <R> ws(
                targetUrl: String,
                builder: ZetaHttpClientBuilder.() -> Unit,
                customHeaders: Map<String, String>?,
                block: suspend DefaultClientWebSocketSession.() -> R,
            ) {}

            override suspend fun status(): Result<SdkStatus> {
                error("not in scope of the test")
            }

            override suspend fun logout(): Result<Unit> = Result.success(Unit)
            override suspend fun close(): Result<Unit> = Result.success(Unit)
            override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
        }

        // Act
        val result = mockClient.authenticate()

        // Assert
        assertTrue(result.isFailure)
    }

    @Test
    fun zetaSdkClient_close_canReturnFailure() = runTest {
        // Arrange
        val mockClient = object : ZetaSdkClient {
            override suspend fun discover(): Result<Unit> = Result.success(Unit)
            override suspend fun register(): Result<Unit> = Result.success(Unit)
            override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
            override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
                return ZetaHttpClientBuilder().build()
            }
            override suspend fun <R> ws(
                targetUrl: String,
                builder: ZetaHttpClientBuilder.() -> Unit,
                customHeaders: Map<String, String>?,
                block: suspend DefaultClientWebSocketSession.() -> R,
            ) {}

            override suspend fun status(): Result<SdkStatus> {
                error("Status not used")
            }

            override suspend fun logout(): Result<Unit> = Result.failure(Exception("Close failed"))
            override suspend fun close(): Result<Unit> = Result.success(Unit)
            override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
        }

        // Act
        val result = mockClient.logout()

        // Assert
        assertTrue(result.isFailure)
    }

    @Test
    fun zetaSdkClient_httpClient_withMultipleConfigurations() {
        // Arrange
        val mockClient = object : ZetaSdkClient {
            override suspend fun discover(): Result<Unit> = Result.success(Unit)
            override suspend fun register(): Result<Unit> = Result.success(Unit)
            override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
            override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
                return ZetaHttpClientBuilder().apply(builder).build()
            }
            override suspend fun <R> ws(
                targetUrl: String,
                builder: ZetaHttpClientBuilder.() -> Unit,
                customHeaders: Map<String, String>?,
                block: suspend DefaultClientWebSocketSession.() -> R,
            ) {}

            override suspend fun status(): Result<SdkStatus> {
                error("status not used in test")
            }

            override suspend fun logout(): Result<Unit> = Result.success(Unit)
            override suspend fun close(): Result<Unit> = Result.success(Unit)
            override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
        }

        // Act
        val client1 = mockClient.httpClient {
            timeouts(connectMs = 1000)
        }
        val client2 = mockClient.httpClient {
            timeouts(connectMs = 5000)
            disableServerValidation(true)
        }

        // Assert
        assertNotNull(client1)
        assertNotNull(client2)
        client1.close()
        client2.close()
    }

    private fun createTestBuildConfig(): BuildConfig {
        return BuildConfig(
            productId = "product-123",
            productVersion = "1.0.0",
            clientName = "TestClient",
            storageConfig = StorageConfig.Default(aesB64Key = "7aae7xXr8rnzVqjpYbosS0CFMrlprkD7jbVotm0fd+w="),
            tpmConfig = object : TpmConfig {},
            authConfig = createTestAuthConfig(),
            platformProductId = createTestPlatformProductId(),
        )
    }
    private fun buildClientWithStorage(storage: SdkStorage): ZetaSdkClient {
        val config = createTestBuildConfig(storage)
        return ZetaSdk.build(resource, config)
    }

    @Test
    fun `status returns NOT_REGISTERED when storage is empty`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.NOT_REGISTERED, result.getOrThrow())
    }

    @Test
    fun `status returns NOT_REGISTERED when auth server linked but registration missing`() = runTest {
        // Arrange
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        ConfigurationStorageImpl(storage, resourceScope).linkResourceToAuthorizationServer(
            getAuthServer(authServerIssuer),
        )

        // Act
        val result = client.status()

        // Assert
        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.NOT_REGISTERED, result.getOrThrow())
    }

    @Test
    fun `status returns REGISTERED_NO_VALID_TOKENS when registered but no tokens saved`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.REGISTERED_NO_VALID_TOKENS, result.getOrThrow())
    }

    @Test
    fun `status returns REGISTERED_NO_VALID_TOKENS when tokens are expired and no refresh token`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)
        setupTokens(
            storage,
            accessToken = "access-token",
            refreshToken = "", // no refresh token
            expiresAt = Clock.System.now().epochSeconds - 60,
        )

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.REGISTERED_NO_VALID_TOKENS, result.getOrThrow())
    }

    @Test
    fun `status returns REGISTERED_NO_VALID_TOKENS when expiresAt is zero and no refresh token`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)
        setupTokens(
            storage,
            accessToken = "access-token",
            refreshToken = "", // no refresh token
            expiresAt = 0L,
        )

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.REGISTERED_NO_VALID_TOKENS, result.getOrThrow())
    }

    @Test
    fun `status returns HAS_REFRESH_TOKEN when only refresh token present`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)
        setupTokens(
            storage, accessToken = "", refreshToken = "refresh-token",
            expiresAt = Clock.System.now().epochSeconds + 3600,
        )

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.HAS_REFRESH_TOKEN, result.getOrThrow())
    }

    @Test
    fun `status returns HAS_REFRESH_TOKEN when tokens expired but refresh token present`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)
        setupTokens(
            storage, accessToken = "access-token", refreshToken = "refresh-token",
            expiresAt = Clock.System.now().epochSeconds - 60,
        )

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.HAS_REFRESH_TOKEN, result.getOrThrow())
    }

    @Test
    fun `status returns HAS_ACCESS_AND_REFRESH_TOKEN when both tokens are valid`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)
        setupTokens(storage)

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN, result.getOrThrow())
    }

    @Test
    fun `status returns HAS_ACCESS_AND_REFRESH_TOKEN when expiry is in the future by 1 second`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)
        setupTokens(storage, expiresAt = Clock.System.now().epochSeconds + 1)

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN, result.getOrThrow())
    }

    @Test
    fun `status reflects tokens written by authenticate flow`() = runTest {
        // Arrange
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        setupRegistration(storage)
        setupTokens(storage)

        client.discover()
        client.register()
        client.authenticate()

        // Act
        val result = client.status()

        // Assert
        assertEquals(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN, result.getOrThrow())
    }

    @Test
    fun `notification client is memoized on the instance`() = runTest {
        // Arrange
        val client = buildClientWithStorage(InMemoryStorage()) as ZetaSdkClientImpl

        // Act
        val first = client.notificationClient
        val second = client.notificationClient

        // Assert
        assertSame(first, second)
        client.close()
    }

    @Test
    fun `notification client creation performs no discovery`() = runTest {
        // Arrange - empty storage: any discovery attempt at creation time would fail loudly
        val client = buildClientWithStorage(InMemoryStorage()) as ZetaSdkClientImpl

        // Act / Assert
        assertNotNull(client.notificationClient)
        client.close()
    }

    @Test
    fun `close succeeds after notification client was created`() = runTest {
        // Arrange
        val client = buildClientWithStorage(InMemoryStorage()) as ZetaSdkClientImpl
        client.notificationClient

        // Act
        val result = client.close()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun `close succeeds after httpClient was created`() = runTest {
        val client = buildClientWithStorage(InMemoryStorage())
        client.httpClient()

        val result = client.close()

        assertTrue(result.isSuccess)
    }

    @Test
    fun `status uses registrationEndpoint as lookup key when it is not blank`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage)
        val authServer = getAuthServer(authServerIssuer).copy(
            registrationEndpoint = "https://auth.example.com/register",
        )
        ConfigurationStorageImpl(storage, resourceScope).linkResourceToAuthorizationServer(authServer)
        ClientRegistrationStorageImpl(storage, resourceScope).saveRegistration(
            authServer = "https://auth.example.com/register",
            registrationResponse = ClientRegistrationResponse(clientId = "client-123"),
        )

        val result = client.status()

        assertTrue(result.isSuccess)
        assertEquals(SdkStatus.REGISTERED_NO_VALID_TOKENS, result.getOrThrow())
    }

    @Test
    fun `buildServiceAccessTokenParams fails when discovery did not run`() = runTest {
        val client = buildClientWithStorage(InMemoryStorage()) as ZetaSdkClientImpl

        val error = assertFailsWith<IllegalArgumentException> { client.buildServiceAccessTokenParams() }

        assertTrue(error.message.orEmpty().contains("Authorization Server metadata unavailable"))
    }

    @Test
    fun `buildServiceAccessTokenParams fails when client is not registered`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage) as ZetaSdkClientImpl
        ConfigurationStorageImpl(storage, resourceScope).linkResourceToAuthorizationServer(getAuthServer(authServerIssuer))

        val error = assertFailsWith<IllegalArgumentException> { client.buildServiceAccessTokenParams() }

        assertTrue(error.message.orEmpty().contains("Client not registered"))
    }

    @Test
    fun `buildServiceAccessTokenParams uses issuer when registrationEndpoint is blank`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage) as ZetaSdkClientImpl
        setupRegistration(storage)

        val params = client.buildServiceAccessTokenParams()

        assertEquals("client-123", params.clientId)
        assertEquals("product-123", params.productId)
        assertEquals("1.0.0", params.productVersion)
        assertEquals(emptyList(), params.scopes)
        assertEquals("", params.audience)
    }

    @Test
    fun `buildServiceAccessTokenParams uses registrationEndpoint when it is not blank`() = runTest {
        val storage = InMemoryStorage()
        val client = buildClientWithStorage(storage) as ZetaSdkClientImpl
        val authServer = getAuthServer(authServerIssuer).copy(
            registrationEndpoint = "https://auth.example.com/register",
        )
        ConfigurationStorageImpl(storage, resourceScope).linkResourceToAuthorizationServer(authServer)
        ClientRegistrationStorageImpl(storage, resourceScope).saveRegistration(
            authServer = "https://auth.example.com/register",
            registrationResponse = ClientRegistrationResponse(clientId = "registered-client"),
        )

        val params = client.buildServiceAccessTokenParams()

        assertEquals("registered-client", params.clientId)
    }

    @Test
    fun `notification client getPushers starts token resolution`() = runTest {
        val client = buildClientWithStorage(InMemoryStorage()) as ZetaSdkClientImpl

        val error = kotlin.runCatching { client.notificationClient!!.getPushers() }.exceptionOrNull()

        assertNotNull(error)
        client.close()
    }

    @Test
    fun `notification client is null when notifications are not configured`() = runTest {
        // Arrange - notificationConfig omitted (null) disables notifications entirely
        val config = createTestBuildConfig(InMemoryStorage()).copy(notificationConfig = null)
        val client = ZetaSdk.build(resource, config) as ZetaSdkClientImpl

        // Act / Assert
        assertNull(client.notificationClient)
        client.close()
    }

    private val resourceScope = ResourceScope(resource, listOf(scope))
    private suspend fun setupRegistration(storage: SdkStorage) {
        ConfigurationStorageImpl(storage, resourceScope).linkResourceToAuthorizationServer(getAuthServer(authServerIssuer))
        ClientRegistrationStorageImpl(storage, resourceScope).saveRegistration(
            authServer = authServerIssuer,
            registrationResponse = ClientRegistrationResponse(clientId = "client-123"),
        )
    }

    private suspend fun setupTokens(
        storage: SdkStorage,
        accessToken: String = "access-token",
        refreshToken: String = "refresh-token",
        expiresAt: Long = Clock.System.now().epochSeconds + 3600,
    ) {
        AuthenticationStorageImpl(storage, resourceScope).saveAccessTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
        )
    }

    private fun createTestAuthConfig(): AuthConfig {
        return AuthConfig(
            listOf(scope),
            300,
            false,
            SmbTokenProvider(
                SmbTokenProvider.Credentials("", "", ""),
            ),
            requiredRoleOid = requiredRoleOid,
        )
    }

    private fun createTestPlatformProductId(): PlatformProductId {
        return PlatformProductId.LinuxProductId(
            platform = "linux",
            "",
            "test",
            "1.0",
        )
    }

    private fun getAuthServer(resource: String): AuthorizationServerMetadata {
        return AuthorizationServerMetadata(
            issuer = resource,
            authorizationEndpoint = "",
            tokenEndpoint = "token_endpoint",
            nonceEndpoint = "",
            openidProvidersEndpoint = "test open id",
            jwksUri = "",
            scopesSupported = listOf(""),
            responseTypesSupported = listOf("TOKEN"),
            responseModesSupported = listOf(""),
            grantTypesSupported = listOf(""),
            tokenEndpointAuthMethodsSupported = listOf(""),
            tokenEndpointAuthSigningAlgValuesSupported = listOf(""),
            serviceDocumentation = "",
            uiLocalesSupported = listOf(""),
            codeChallengeMethodsSupported = listOf(""),
            registrationEndpoint = "",
        )
    }

    private fun createTestBuildConfig(storage: SdkStorage? = InMemoryStorage()): BuildConfig {
        return BuildConfig(
            productId = "product-123",
            productVersion = "1.0.0",
            clientName = "TestClient",
            storageConfig = StorageConfig.Custom(storage!!),
            tpmConfig = object : TpmConfig {},
            authConfig = createTestAuthConfig(),
            platformProductId = createTestPlatformProductId(),
            notificationConfig = NotificationConfig(),
        )
    }
}
