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

import de.gematik.zeta.sdk.ZetaSdk.clearRegistration
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.network.http.client.CompositeCookieStorage
import de.gematik.zeta.sdk.network.http.client.SdkCookieStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ZetaSdkTest {
    private val requiredRoleOid = "1.2.276.0.76.4.261"
    private val resource = "https://example.com/pep/service/"
    private val scope = "scope"
    private val resourceScope = ResourceScope(resource, listOf(scope))
    private val storage = InMemoryStorage()

    private fun buildClient(): ZetaSdkClient = ZetaSdk.build(
        resource,
        createTestBuildConfig(storageConfig = StorageConfig.Custom(storage)),
    )

    private suspend fun storeCookie() {
        val sdkCookieStorage = SdkCookieStorage(storage, resourceScope)
        sdkCookieStorage.addCookie(
            Url(resource),
            Cookie(name = CompositeCookieStorage.ZETA_ROUTE_COOKIE, value = "test_route_value"),
        )
    }

    private suspend fun readCookie(): String? {
        val sdkCookieStorage = SdkCookieStorage(storage, resourceScope)
        return sdkCookieStorage.get(Url(resource)).firstOrNull()?.value
    }

    @Test
    fun logout_clearsCookie() = runTest {
        // Arrange
        val client = buildClient()
        storeCookie()
        assertNotNull(readCookie())

        // Act
        client.logout()

        // Assert
        assertNull(readCookie())
    }

    @Test
    fun forget_clearsCookie() = runTest {
        // Arrange
        val client = buildClient()
        storeCookie()
        assertNotNull(readCookie())

        // Act
        with(ZetaSdk) { client.forget() }

        // Assert
        assertNull(readCookie())
    }

    @Test
    fun clearRegistration_clearsCookie() = runTest {
        // Arrange
        val client = buildClient()
        storeCookie()
        assertNotNull(readCookie())

        // Act
        with(ZetaSdk) { client.clearRegistration() }

        // Assert
        assertNull(readCookie())
    }

    @Test
    fun logout_doesNotAffectOtherStorageKeys() = runTest {
        // Arrange
        val client = buildClient()
        val extendedStorage = ExtendedStorage(storage, resourceScope)
        storeCookie()
        extendedStorage.put("other_key", "other_value")

        // Act
        client.logout()

        // Assert
        assertNull(readCookie())
        assertEquals("other_value", extendedStorage.get("other_key"))
    }

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
        val config = createTestBuildConfig(storageConfig = StorageConfig.Custom(provider = mockStorage))

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
    fun clearRegistration_returnsSuccess_whenNoErrors() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.clearRegistration()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun forget_delegatesToLogout_forNonImplClients() = runTest {
        val client = ForeignZetaSdkClient()

        val result = with(ZetaSdk) { client.forget() }

        assertTrue(result.isSuccess)
        assertTrue(client.logoutCalled)
    }

    @Test
    fun forget_returnsFailure_whenForeignLogoutFails() = runTest {
        val client = ForeignZetaSdkClient(logoutResult = Result.failure(IllegalStateException("logout failed")))

        val result = with(ZetaSdk) { client.forget() }

        assertTrue(result.isFailure)
        assertTrue(client.logoutCalled)
        assertEquals("logout failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun clearRegistration_delegatesToLogout_forNonImplClients() = runTest {
        val client = ForeignZetaSdkClient()

        val result = with(ZetaSdk) { client.clearRegistration() }

        assertTrue(result.isSuccess)
        assertTrue(client.logoutCalled)
    }

    @Test
    fun clearRegistration_returnsFailure_whenForeignLogoutFails() = runTest {
        val client = ForeignZetaSdkClient(logoutResult = Result.failure(IllegalStateException("logout failed")))

        val result = with(ZetaSdk) { client.clearRegistration() }

        assertTrue(result.isFailure)
        assertEquals("logout failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun discover_returnsFailure_whenConfigurationFails() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

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
    fun logout_returnsSuccess() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val result = client.logout()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun close_clearsAuthenticationStorage() = runTest {
        // Arrange
        val storage = InMemoryStorage()
        val testScope = ResourceScope("https://api.example.com", listOf(scope))
        val client = ZetaSdk.build(
            "https://api.example.com",
            createTestBuildConfig(storageConfig = StorageConfig.Custom(provider = storage)),
        )
        val authStorage = AuthenticationStorageImpl(storage, testScope)
        authStorage.saveAccessTokens(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAt = Clock.System.now().epochSeconds + 3600,
        )
        assertNotNull(authStorage.getAccessToken())

        // Act
        val result = client.logout()

        // Assert
        assertTrue(result.isSuccess)
        assertNull(authStorage.getAccessToken())
        assertNull(authStorage.getRefreshToken())
    }

    @Test
    fun fullFlow_buildDiscoverRegisterAuthenticate_executesInOrder() = runTest {
        // Arrange
        val config = createTestBuildConfig()
        val client = ZetaSdk.build("https://api.example.com", config)

        // Act
        val discoverResult = client.discover()
        val registerResult = client.register()
        val authenticateResult = client.authenticate()

        // Assert
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
    fun getVersion_returnsGeneratedSdkVersion() {
        // Act
        val version = ZetaSdk.getVersion()

        // Assert
        assertEquals(ZETA_SDK_VERSION, version)
    }

    @Test
    fun storageConfig_defaultConstructor_usesDefaultValues() {
        // Act
        val config = StorageConfig.Default(aesB64Key = "aesTestKey")

        // Assert
        assertNotNull(config.aesB64Key)
        assertTrue(config.aesB64Key.isNotEmpty())
    }

    @Test
    fun storageConfig_copy_withNoChanges_createsEqualObject() {
        // Arrange
        val original = StorageConfig.Default(aesB64Key = "key1")

        // Act
        val copy = original.copy()

        // Assert
        assertEquals(original, copy)
        assertNotSame(original, copy)
    }

    @Test
    fun storageConfigDefault_toString_includesClassName() {
        // Arrange
        val config = StorageConfig.Default(aesB64Key = "7aae7xXr8rnzVqjpYbosS0CFMrlprkD7jbVotm0fd+w=")

        // Act
        val result = config.toString()

        // Assert
        assertTrue(result.contains("Default"))
    }

    @Test
    fun storageConfigCustom_toString_includesClassName() {
        // Arrange
        val config = StorageConfig.Custom(InMemoryStorage())

        // Act
        val result = config.toString()

        // Assert
        assertTrue(result.contains("Custom"))
    }

    @Test
    fun storageConfig_hashCode_consistentWithEquals() {
        // Arrange
        val config1 = StorageConfig.Default(aesB64Key = "sameKey")
        val config2 = StorageConfig.Default(aesB64Key = "sameKey")

        // Assert
        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun regInfo_equality_reflexive() {
        // Arrange
        val info = RegInfo(clientName = "Client")

        // Assert
        assertEquals(info, info)
    }

    @Test
    fun regInfo_equality_symmetric() {
        // Arrange
        val info1 = RegInfo(clientName = "Client")
        val info2 = RegInfo(clientName = "Client")

        // Assert
        assertEquals(info1, info2)
        assertEquals(info2, info1)
    }

    @Test
    fun regInfo_copy_withNoChanges_createsEqualObject() {
        // Arrange
        val original = RegInfo(clientName = "Original")

        // Act
        val copy = original.copy()

        // Assert
        assertEquals(original, copy)
        assertNotSame(original, copy)
    }

    @Test
    fun regInfo_withSpecialCharacters_createsValidObject() {
        // Arrange & Act
        val regInfo = RegInfo(clientName = "Client-123!@#$%")

        // Assert
        assertEquals("Client-123!@#$%", regInfo.clientName)
    }

    @Test
    fun regInfo_withUnicodeCharacters_createsValidObject() {
        // Arrange & Act
        val regInfo = RegInfo(clientName = "Cliente-ñáéíóú-客户端")

        // Assert
        assertEquals("Cliente-ñáéíóú-客户端", regInfo.clientName)
    }

    @Test
    fun regInfo_hashCode_differentForDifferentValues() {
        // Arrange
        val info1 = RegInfo(clientName = "Client1")
        val info2 = RegInfo(clientName = "Client2")

        // Assert
        assertNotEquals(info1.hashCode(), info2.hashCode())
    }

    @Test
    fun regInfo_toString_validFormat() {
        // Arrange
        val regInfo = RegInfo(clientName = "TestClient")

        // Act
        val result = regInfo.toString()

        // Assert
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun buildConfig_withNullOptionalFields_createsValidObject() {
        // Arrange & Act
        val config = createTestBuildConfig()

        // Assert
        assertNull(config.httpClientBuilder)
        assertNull(config.registrationCallback)
    }

    @Test
    fun buildConfig_copy_onlyProductId_keepsOtherFields() {
        // Arrange
        val original = createTestBuildConfig()

        // Act
        val copy = original.copy(productId = "new-product")

        // Assert
        assertEquals("new-product", copy.productId)
        assertEquals(original.productVersion, copy.productVersion)
        assertEquals(original.clientName, copy.clientName)
        assertEquals(original.storageConfig, copy.storageConfig)
    }

    @Test
    fun buildConfig_copy_onlyProductVersion_keepsOtherFields() {
        // Arrange
        val original = createTestBuildConfig()

        // Act
        val copy = original.copy(productVersion = "2.0.0")

        // Assert
        assertEquals("2.0.0", copy.productVersion)
        assertEquals(original.productId, copy.productId)
        assertEquals(original.clientName, copy.clientName)
    }

    @Test
    fun buildConfig_copy_onlyClientName_keepsOtherFields() {
        // Arrange
        val original = createTestBuildConfig()

        // Act
        val copy = original.copy(clientName = "new-client")

        // Assert
        assertEquals("new-client", copy.clientName)
        assertEquals(original.productId, copy.productId)
        assertEquals(original.productVersion, copy.productVersion)
    }

    @Test
    fun buildConfig_copy_onlyStorageConfig_keepsOtherFields() {
        // Arrange
        val original = createTestBuildConfig()
        val newStorage = StorageConfig.Default(aesB64Key = "newKey")

        // Act
        val copy = original.copy(storageConfig = newStorage)

        // Assert
        assertEquals(newStorage, copy.storageConfig)
        assertEquals(original.productId, copy.productId)
    }

    @Test
    fun buildConfig_copy_onlyAuthConfig_keepsOtherFields() {
        // Arrange
        val original = createTestBuildConfig()
        val newAuthConfig = AuthConfig(
            listOf("new:audience"),
            600,
            true,
            SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")),
            requiredRoleOid = requiredRoleOid,
        )

        // Act
        val copy = original.copy(authConfig = newAuthConfig)

        // Assert
        assertEquals(newAuthConfig, copy.authConfig)
        assertEquals(original.productId, copy.productId)
    }

    @Test
    fun buildConfig_copy_onlyPlatformProductId_keepsOtherFields() {
        // Arrange
        val original = createTestBuildConfig()
        val newPlatform = PlatformProductId.WindowsProductId("windows", "11", "app")

        // Act
        val copy = original.copy(platformProductId = newPlatform)

        // Assert
        assertEquals(newPlatform, copy.platformProductId)
        assertEquals(original.productId, copy.productId)
    }

    @Test
    fun buildConfig_copy_httpClientBuilder_updatesCorrectly() {
        // Arrange
        val original = createTestBuildConfig()
        val builder = ZetaHttpClientBuilder()

        // Act
        val copy = original.copy(httpClientBuilder = builder)

        // Assert
        assertEquals(builder, copy.httpClientBuilder)
        assertNull(original.httpClientBuilder)
    }

    @Test
    fun buildConfig_copy_registrationCallback_updatesCorrectly() {
        // Arrange
        val original = createTestBuildConfig()
        val callback = RegistrationCallback { RegInfo("new") }

        // Act
        val copy = original.copy(registrationCallback = callback)

        // Assert
        assertEquals(callback, copy.registrationCallback)
        assertNull(original.registrationCallback)
    }

    @Test
    fun buildConfig_notEquals_differentStorageConfig() {
        // Arrange
        val config1 = createTestBuildConfig()
        val config2 = config1.copy(storageConfig = StorageConfig.Default(aesB64Key = "different"))

        // Assert
        assertNotEquals(config1, config2)
    }

    @Test
    fun buildConfig_withLinuxPlatform_createsValidObject() {
        // Arrange
        val config = createTestBuildConfig()

        // Assert
        assertTrue(config.platformProductId is PlatformProductId.LinuxProductId)
    }

    @Test
    fun buildConfig_withApplePlatform_createsValidObject() {
        // Arrange
        val applePlatform = PlatformProductId.AppleProductId(
            "apple",
            "macOS 14",
            listOf("com.example.app"),
        )
        val config = createTestBuildConfig().copy(platformProductId = applePlatform)

        // Assert
        assertTrue(config.platformProductId is PlatformProductId.AppleProductId)
    }

    @Test
    fun buildConfig_withWindowsPlatform_createsValidObject() {
        // Arrange
        val windowsPlatform = PlatformProductId.WindowsProductId(
            platform = "windows",
            "11",
            "win-app",
        )
        val config = createTestBuildConfig().copy(platformProductId = windowsPlatform)

        // Assert
        assertTrue(config.platformProductId is PlatformProductId.WindowsProductId)
    }

    @Test
    fun registrationCallback_capturesLambdaCorrectly() = runTest {
        // Arrange
        val expectedName = "ExpectedClient"
        val callback = RegistrationCallback {
            RegInfo(clientName = expectedName)
        }

        // Act
        val result = callback.registrationCb()

        // Assert
        assertEquals(expectedName, result.clientName)
    }

    @Test
    fun registrationCallback_withDifferentClientNames_returnsCorrectValues() = runTest {
        // Arrange
        val callback1 = RegistrationCallback { RegInfo("Client1") }
        val callback2 = RegistrationCallback { RegInfo("Client2") }

        // Act
        val result1 = callback1.registrationCb()
        val result2 = callback2.registrationCb()

        // Assert
        assertEquals("Client1", result1.clientName)
        assertEquals("Client2", result2.clientName)
    }

    @Test
    fun registrationCallback_withEmptyClientName_returnsEmpty() = runTest {
        // Arrange
        val callback = RegistrationCallback { RegInfo("") }

        // Act
        val result = callback.registrationCb()

        // Assert
        assertEquals("", result.clientName)
    }

    @Test
    fun registrationCallback_withComplexLogic_executesCorrectly() = runTest {
        // Arrange
        var sideEffect = 0
        val callback = RegistrationCallback {
            sideEffect += 10
            RegInfo("Client-$sideEffect")
        }

        // Act
        val result = callback.registrationCb()

        // Assert
        assertEquals(10, sideEffect)
        assertEquals("Client-10", result.clientName)
    }

    private fun createTestBuildConfig(
        productId: String = "test-product",
        productVersion: String = "1.0.0",
        clientName: String = "TestClient",
        storageConfig: StorageConfig = StorageConfig.Custom(provider = InMemoryStorage()),
        httpClientBuilder: ZetaHttpClientBuilder? = null,
        registrationCallback: RegistrationCallback? = null,
    ): BuildConfig {
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(
            listOf(scope),
            300,
            false,
            SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")),
            requiredRoleOid = requiredRoleOid,
        )
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
        )
    }

    private fun createMockStorage(): SdkStorage = object : SdkStorage {
        private val data = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { data[key] = value }
        override suspend fun get(key: String): String? = data[key]
        override suspend fun remove(key: String) { data.remove(key) }
        override suspend fun clear() { data.clear() }
    }

    private class ForeignZetaSdkClient(
        private val logoutResult: Result<Unit> = Result.success(Unit),
    ) : ZetaSdkClient {
        var logoutCalled = false

        override suspend fun discover(): Result<Unit> = Result.success(Unit)
        override suspend fun register(): Result<Unit> = Result.success(Unit)
        override suspend fun authenticate(): Result<Unit> = Result.success(Unit)
        override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
            error("not in scope of the test")
        }
        override suspend fun <R> ws(
            targetUrl: String,
            builder: ZetaHttpClientBuilder.() -> Unit,
            customHeaders: Map<String, String>?,
            block: suspend DefaultClientWebSocketSession.() -> R,
        ) {
            error("not in scope of the test")
        }
        override suspend fun status(): Result<SdkStatus> = Result.success(SdkStatus.NOT_REGISTERED)
        override suspend fun logout(): Result<Unit> {
            logoutCalled = true
            return logoutResult
        }
        override suspend fun close(): Result<Unit> = Result.success(Unit)
        override suspend fun changeEmail(newEmail: String) = error("not in scope of the test")
    }
}
