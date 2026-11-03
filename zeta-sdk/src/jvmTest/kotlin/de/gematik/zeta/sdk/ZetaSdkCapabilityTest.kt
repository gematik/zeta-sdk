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
import de.gematik.zeta.sdk.authentication.identity.ChallengeType
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.identity.ClientManagement
import de.gematik.zeta.sdk.authentication.identity.ClientManagementApiClient
import de.gematik.zeta.sdk.authentication.identity.EmailChallenge
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient
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
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.statement.HttpResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        mockDiscover(CapabilityResult.Error("SERVICE_DISCOVERY_ERROR", "discovery error", httpResponse))
        val client = buildClient()

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        assertTrue("SERVICE_DISCOVERY_ERROR" in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun changeEmail_doesNotRegisterImplicitly() = runTest {
        mockDiscover()
        val api = FakeClientManagementApi()
        val client = buildClient(api = api)

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { clientRegistrationHandler.handle(any(), any()) }
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun changeEmail_returnsNotRegistered_whenAuthServerMetadataMissing() = runTest {
        mockDiscover()
        val client = buildClient()

        val result = client.changeEmail("new@example.de")

        assertTrue(result.isFailure)
        assertEquals(ClientManagementOperations.NOT_REGISTERED, result.exceptionOrNull()?.message)
    }

    @Test
    fun changeEmail_returnsNotRegistered_whenNoClientIdStored() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val client = buildClient(storage)
        linkAuthServer(storage)

        val result = client.changeEmail("new@example.de")

        assertEquals(ClientManagementOperations.NOT_REGISTERED, result.exceptionOrNull()?.message)
    }

    @Test
    fun changeEmail_returnsNotRegistered_whenClientIdIsBlank() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val client = buildClient(storage)
        linkAuthServer(storage)
        saveClientId(storage, authServerIssuer, "  ")

        val result = client.changeEmail("new@example.de")

        assertEquals(ClientManagementOperations.NOT_REGISTERED, result.exceptionOrNull()?.message)
    }

    @Test
    fun changeEmail_delegates_whenRegisteredUnderIssuer() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val api = FakeClientManagementApi(changeEmailResponse = ChangeEmailResponse.Verified)
        val client = buildClient(storage, api)
        linkAuthServer(storage)
        saveClientId(storage, authServerIssuer, "client-123")

        val result = client.changeEmail("new@example.de")

        assertEquals(ChangeEmailResponse.Verified, result.getOrThrow())
        assertEquals(listOf("changeEmail:new@example.de"), api.calls)
    }

    @Test
    fun changeEmail_delegates_whenRegisteredUnderRegistrationEndpoint() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val registrationEndpoint = "https://auth.example.com/register"
        val api = FakeClientManagementApi(changeEmailResponse = ChangeEmailResponse.Pending(challengeType = ChallengeType.EMAIL_OTP, emailHint = "n***@example.de"))
        val client = buildClient(storage, api)
        linkAuthServer(storage, authServer(registrationEndpoint = registrationEndpoint))
        saveClientId(storage, registrationEndpoint, "registered-client")

        val result = client.changeEmail("new@example.de")

        assertIs<ChangeEmailResponse.Pending>(result.getOrThrow())
    }

    @Test
    fun resendEmailChangeCode_delegates_whenRegistered() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val api = FakeClientManagementApi()
        val client = buildClient(storage, api)
        linkAuthServer(storage)
        saveClientId(storage, authServerIssuer, "client-123")

        val result = client.resendEmailChangeCode()

        assertEquals(EmailChallenge(challengeType = ChallengeType.EMAIL_OTP), result.getOrThrow())
        assertEquals(listOf("resendEmailChangeCode"), api.calls)
    }

    @Test
    fun verifyEmailChange_delegates_whenRegistered() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val api = FakeClientManagementApi()
        val client = buildClient(storage, api)
        linkAuthServer(storage)
        saveClientId(storage, authServerIssuer, "client-123")

        client.verifyEmailChange("123456").getOrThrow()

        assertEquals(listOf("verifyEmailChange:123456"), api.calls)
    }

    @Test
    fun listClients_returnsNotRegistered_whenNoClientIdStored() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val api = FakeClientManagementApi()
        val client = buildClient(storage, api)
        linkAuthServer(storage)

        val result = client.listClients()

        assertEquals(ClientManagementOperations.NOT_REGISTERED, result.exceptionOrNull()?.message)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun listClients_returnsClients_whenRegistered() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val clients = listOf(RegisteredClient(id = "client-123", name = "Pixel 9"), RegisteredClient(id = "other", name = "iPhone 15"))
        val client = buildClient(storage, FakeClientManagementApi(clients = clients))
        registerUnderIssuer(storage, "client-123")

        assertEquals(clients, client.listClients().getOrThrow())
    }

    @Test
    fun deleteClient_ownClientDeleted_clearsStoredRegistration() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val client = buildClient(storage, FakeClientManagementApi())
        registerUnderIssuer(storage, "client-123")

        client.deleteClient("client-123").getOrThrow()

        assertNull(storedClientId(storage))
    }

    @Test
    fun deleteClient_otherClientDeleted_keepsStoredRegistration() = runTest {
        mockDiscover()
        val storage = InMemoryStorage()
        val client = buildClient(storage, FakeClientManagementApi())
        registerUnderIssuer(storage, "client-123")

        client.deleteClient("other-client").getOrThrow()

        assertEquals("client-123", storedClientId(storage))
    }

    @Test
    fun close_succeedsAfterClientManagementWasCreated() = runTest {
        val client = ZetaSdkClientImpl(resourceScope, buildTestConfig(), SystemZetaClock)
        assertNotNull(client.clientManagement)

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

    private fun mockDiscover(result: CapabilityResult = CapabilityResult.Done) {
        coEvery { configHandler.handle(FlowNeed.ConfigurationFiles, any()) } returns result
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

    private fun buildClient(
        storage: InMemoryStorage = InMemoryStorage(),
        api: ClientManagementApiClient? = null,
    ): ZetaSdkClientImpl {
        val client = ZetaSdkClientImpl(resourceScope, buildTestConfig(storage), SystemZetaClock)
        client.injectLazyDelegate("configHandler", configHandler)
        client.injectLazyDelegate("clientRegistrationHandler", clientRegistrationHandler)
        client.injectLazyDelegate("authHandler", authHandler)
        if (api != null) {
            client.injectLazyDelegate("clientManagement", ClientManagement(api))
        }
        return client
    }

    private suspend fun registerUnderIssuer(storage: InMemoryStorage, clientId: String) {
        linkAuthServer(storage)
        saveClientId(storage, authServerIssuer, clientId)
    }

    private suspend fun linkAuthServer(
        storage: InMemoryStorage,
        authServer: AuthorizationServerMetadata = authServer(),
    ) {
        ConfigurationStorageImpl(storage, resourceScope, clock = SystemZetaClock).linkResourceToAuthorizationServer(authServer)
    }

    private suspend fun saveClientId(storage: InMemoryStorage, authServerKey: String, clientId: String) {
        ClientRegistrationStorageImpl(storage, resourceScope).saveRegistration(
            authServer = authServerKey,
            registrationResponse = ClientRegistrationResponse(clientId = clientId),
        )
    }

    private suspend fun storedClientId(storage: InMemoryStorage): String? =
        ClientRegistrationStorageImpl(storage, resourceScope).getClientId(authServerIssuer)

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

    private class FakeClientManagementApi(
        private val clients: List<RegisteredClient> = emptyList(),
        private val changeEmailResponse: ChangeEmailResponse = ChangeEmailResponse.Verified,
    ) : ClientManagementApiClient {
        val calls = mutableListOf<String>()

        override suspend fun listClients(): List<RegisteredClient> {
            calls += "listClients"
            return clients
        }

        override suspend fun deleteClient(id: String) {
            calls += "deleteClient:$id"
        }

        override suspend fun changeEmail(newEmail: String): ChangeEmailResponse {
            calls += "changeEmail:$newEmail"
            return changeEmailResponse
        }

        override suspend fun resendEmailChangeCode(): EmailChallenge {
            calls += "resendEmailChangeCode"
            return EmailChallenge(challengeType = ChallengeType.EMAIL_OTP)
        }

        override suspend fun verifyEmailChange(code: String) {
            calls += "verifyEmailChange:$code"
        }
    }
}
