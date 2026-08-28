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
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailClient
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorageImpl
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationResponse
import de.gematik.zeta.sdk.configuration.ConfigurationStorageImpl
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.handler.ClientRegistrationHandler
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler
import de.gematik.zeta.sdk.flow.handler.EnsureAccessTokenHandler
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.statement.HttpResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZetaSdkClientImplCapabilityTest {

    @Test
    fun discover_returnsSuccess_whenHandlerReturnsDone() = runTest {
        // Arrange
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns CapabilityResult.Done
        val client = buildClient()

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun discover_returnsFailure_whenHandlerReturnsError() = runTest {
        // Arrange
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns
            CapabilityResult.Error("SERVICE_DISCOVERY_ERROR", "discovery error", httpResponse)
        val client = buildClient()

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("SERVICE_DISCOVERY_ERROR" in message)
        assertTrue("discovery error" in message)
    }

    @Test
    fun discover_treatsRetryRequestAsSuccess_documentingCurrentBehavior() = runTest {
        // Arrange
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns
            CapabilityResult.RetryRequest()
        val client = buildClient()

        // Act
        val result = client.discover()

        // Assert
        assertTrue(result.isSuccess, "documents current (possibly unintended) orThrow() behavior")
    }

    @Test
    fun register_returnsSuccess_whenHandlerReturnsDone() = runTest {
        // Arrange
        coEvery { clientRegistrationHandler.handle(FlowNeed.ClientRegistration, any()) } returns
            CapabilityResult.Done
        val client = buildClient()

        // Act
        val result = client.register()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun register_returnsFailure_whenHandlerReturnsError() = runTest {
        // Arrange
        coEvery { clientRegistrationHandler.handle(FlowNeed.ClientRegistration, any()) } returns
            CapabilityResult.Error("REGISTRATION_FAILED_ERROR", "registration error", httpResponse)
        val client = buildClient()

        // Act
        val result = client.register()

        // Assert
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("REGISTRATION_FAILED_ERROR" in message)
        assertTrue("registration error" in message)
    }

    @Test
    fun authenticate_returnsSuccess_whenHandlerReturnsDone() = runTest {
        // Arrange
        coEvery { authHandler.handle(FlowNeed.Authentication, any()) } returns CapabilityResult.Done
        val client = buildClient()

        // Act
        val result = client.authenticate()

        // Assert
        assertTrue(result.isSuccess)
    }

    @Test
    fun authenticate_returnsFailure_whenHandlerReturnsError() = runTest {
        // Arrange
        coEvery { authHandler.handle(FlowNeed.Authentication, any()) } returns
            CapabilityResult.Error("AUTH_FAILED_ERROR", "auth error", httpResponse)
        val client = buildClient()

        // Act
        val result = client.authenticate()

        // Assert
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("AUTH_FAILED_ERROR" in message)
        assertTrue("auth error" in message)
    }

    @Test
    fun changeEmail_returnsFailure_whenDiscoverFails() = runTest {
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns
            CapabilityResult.Error("SERVICE_DISCOVERY_ERROR", "discovery error", httpResponse)
        val client = buildClient()

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        assertTrue("SERVICE_DISCOVERY_ERROR" in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun changeEmail_returnsFailure_whenRegisterFails() = runTest {
        mockDiscoverAndRegister(
            discover = CapabilityResult.Done,
            register = CapabilityResult.Error("REGISTRATION_FAILED_ERROR", "registration error", httpResponse),
        )
        val client = buildClient()

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        assertTrue("REGISTRATION_FAILED_ERROR" in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun changeEmail_returnsFailure_whenAuthServerMetadataMissing() = runTest {
        mockDiscoverAndRegister()
        val client = buildClient()

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        assertTrue("Authorization Server metadata unavailable" in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun changeEmail_returnsFailure_whenClientIsNotRegistered() = runTest {
        mockDiscoverAndRegister()
        val storage = InMemoryStorage()
        val client = buildClient(storage)
        linkAuthServer(storage)

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        assertTrue("Client not registered" in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun changeEmail_returnsFailure_whenClientIdIsBlank() = runTest {
        mockDiscoverAndRegister()
        val storage = InMemoryStorage()
        val client = buildClient(storage)
        linkAuthServer(storage)
        saveClientId(storage, authServerIssuer, "  ")

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        assertTrue("Client not registered" in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun changeEmail_usesIssuerWhenRegistrationEndpointIsBlank() = runTest {
        mockDiscoverAndRegister()
        val storage = InMemoryStorage()
        val changeEmailClient = mockk<ChangeEmailClient>()
        coEvery { changeEmailClient.changeEmail(any(), any(), any()) } returns ChangeEmailResponse("verified")
        val client = buildClient(storage, changeEmailClient)
        linkAuthServer(storage)
        saveClientId(storage, authServerIssuer, "client-123")

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isSuccess)
        assertEquals("verified", result.getOrThrow().status)
        coVerify { changeEmailClient.changeEmail(authServerIssuer, "client-123", "new@example.de") }
    }

    @Test
    fun changeEmail_usesRegistrationEndpointWhenItIsNotBlank() = runTest {
        mockDiscoverAndRegister()
        val storage = InMemoryStorage()
        val registrationEndpoint = "https://auth.example.com/register"
        val changeEmailClient = mockk<ChangeEmailClient>()
        coEvery { changeEmailClient.changeEmail(any(), any(), any()) } returns ChangeEmailResponse("pending")
        val client = buildClient(storage, changeEmailClient)
        linkAuthServer(storage, authServer(registrationEndpoint = registrationEndpoint))
        saveClientId(storage, registrationEndpoint, "registered-client")

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isSuccess)
        assertEquals("pending", result.getOrThrow().status)
        coVerify { changeEmailClient.changeEmail(authServerIssuer, "registered-client", "new@example.de") }
    }

    @Test
    fun close_succeedsAfterChangeEmailClientWasCreated() = runTest {
        val client = buildClient()
        client.forceChangeEmailClientInit()

        val result = client.close()

        assertTrue(result.isSuccess)
    }

    private val resource = "resx"
    private val scope = "scope"
    private val authServerIssuer = "https://auth.example.com"
    private val resourceScope = ResourceScope(resource, listOf(scope))
    private val configHandler: ConfigurationHandler = mockk()
    private val clientRegistrationHandler: ClientRegistrationHandler = mockk()
    private val authHandler: EnsureAccessTokenHandler = mockk()
    private val httpResponse: HttpResponse = mockk()

    private fun mockDiscoverAndRegister(
        discover: CapabilityResult = CapabilityResult.Done,
        register: CapabilityResult = CapabilityResult.Done,
    ) {
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns discover
        coEvery { clientRegistrationHandler.handle(FlowNeed.ClientRegistration, any()) } returns register
    }

    private fun buildTestConfig(storage: InMemoryStorage = InMemoryStorage()): BuildConfig {
        val tpmConfig = object : TpmConfig {}
        val authConfig = AuthConfig(
            listOf(scope),
            300,
            false,
            SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")),
            requiredRoleOid = "1.2.276.0.76.4.261",
        )
        val platformProductId = PlatformProductId.LinuxProductId("", "", "", "")
        return BuildConfig(
            productId = "test-product",
            productVersion = "1.0.0",
            clientName = "TestClient",
            storageConfig = StorageConfig.Custom(storage),
            tpmConfig = tpmConfig,
            authConfig = authConfig,
            platformProductId = platformProductId,
        )
    }

    private fun ZetaSdkClientImpl.injectLazyDelegate(propertyName: String, value: Any) {
        val field = ZetaSdkClientImpl::class.java.getDeclaredField("$propertyName\$delegate")
        field.isAccessible = true
        field.set(this, lazyOf(value))
    }

    private fun ZetaSdkClientImpl.forceChangeEmailClientInit() {
        val field = ZetaSdkClientImpl::class.java.getDeclaredField("changeEmailClient\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as Lazy<ChangeEmailClient>).value
    }

    private fun buildClient(
        storage: InMemoryStorage = InMemoryStorage(),
        changeEmailClient: ChangeEmailClient? = null,
    ): ZetaSdkClientImpl {
        val client = ZetaSdkClientImpl(resourceScope, buildTestConfig(storage))
        client.injectLazyDelegate("configHandler", configHandler)
        client.injectLazyDelegate("clientRegistrationHandler", clientRegistrationHandler)
        client.injectLazyDelegate("authHandler", authHandler)
        if (changeEmailClient != null) {
            client.injectLazyDelegate("changeEmailClient", changeEmailClient)
        }
        return client
    }

    private suspend fun linkAuthServer(
        storage: InMemoryStorage,
        authServer: AuthorizationServerMetadata = authServer(),
    ) {
        ConfigurationStorageImpl(storage, resourceScope).linkResourceToAuthorizationServer(authServer)
    }

    private suspend fun saveClientId(storage: InMemoryStorage, authServerKey: String, clientId: String) {
        ClientRegistrationStorageImpl(storage, resourceScope).saveRegistration(
            authServer = authServerKey,
            registrationResponse = ClientRegistrationResponse(clientId = clientId),
        )
    }

    private fun authServer(registrationEndpoint: String = ""): AuthorizationServerMetadata =
        AuthorizationServerMetadata(
            issuer = authServerIssuer,
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
            registrationEndpoint = registrationEndpoint,
        )
}
