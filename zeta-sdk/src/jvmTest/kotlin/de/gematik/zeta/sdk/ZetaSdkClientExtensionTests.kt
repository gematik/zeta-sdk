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

import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZetaSdkClientExtensionTest {
    @Test
    fun close_returnsTrue_whenClientCloseSucceeds() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            closeResult = Result.success(Unit)
        }

        // Act
        val result = ZetaSdkClientExtension.close(client)

        // Assert
        assertTrue(result)
        assertTrue(client.closeCalled)
    }

    @Test
    fun close_returnsFalse_whenClientCloseFails() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            closeResult = Result.failure(Exception("Close failed"))
        }

        // Act
        val result = ZetaSdkClientExtension.close(client)

        // Assert
        assertFalse(result)
        assertTrue(client.closeCalled)
    }

    @Test
    fun authenticate_returnsTrue_whenClientAuthenticateSucceeds() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            authenticateResult = Result.success(Unit)
        }

        // Act
        val result = ZetaSdkClientExtension.authenticate(client)

        // Assert
        assertTrue(result)
        assertTrue(client.authenticateCalled)
    }

    @Test
    fun authenticate_returnsFalse_whenClientAuthenticateFails() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            authenticateResult = Result.failure(Exception("Authentication failed"))
        }

        // Act
        val result = ZetaSdkClientExtension.authenticate(client)

        // Assert
        assertFalse(result)
        assertTrue(client.authenticateCalled)
    }

    @Test
    fun register_returnsTrue_whenClientRegisterSucceeds() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            registerResult = Result.success(Unit)
        }

        // Act
        val result = ZetaSdkClientExtension.register(client)

        // Assert
        assertTrue(result)
        assertTrue(client.registerCalled)
    }

    @Test
    fun register_returnsFalse_whenClientRegisterFails() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            registerResult = Result.failure(Exception("Registration failed"))
        }

        // Act
        val result = ZetaSdkClientExtension.register(client)

        // Assert
        assertFalse(result)
        assertTrue(client.registerCalled)
    }

    @Test
    fun discover_returnsTrue_whenClientDiscoverSucceeds() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            discoverResult = Result.success(Unit)
        }

        // Act
        val result = ZetaSdkClientExtension.discover(client)

        // Assert
        assertTrue(result)
        assertTrue(client.discoverCalled)
    }

    @Test
    fun discover_returnsFalse_whenClientDiscoverFails() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            discoverResult = Result.failure(Exception("Discovery failed"))
        }

        // Act
        val result = ZetaSdkClientExtension.discover(client)

        // Assert
        assertFalse(result)
        assertTrue(client.discoverCalled)
    }

    @Test
    fun authenticate_handlesRuntimeException_gracefully() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            authenticateResult = Result.failure(RuntimeException("Runtime error"))
        }

        // Act
        val result = ZetaSdkClientExtension.authenticate(client)

        // Assert
        assertFalse(result)
    }

    @Test
    fun register_handlesIllegalStateException_gracefully() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            registerResult = Result.failure(IllegalStateException("Invalid state"))
        }

        // Act
        val result = ZetaSdkClientExtension.register(client)

        // Assert
        assertFalse(result)
    }

    @Test
    fun discover_handlesIllegalArgumentException_gracefully() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            discoverResult = Result.failure(IllegalArgumentException("Invalid argument"))
        }

        // Act
        val result = ZetaSdkClientExtension.discover(client)

        // Assert
        assertFalse(result)
    }

    @Test
    fun close_handlesIOException_gracefully() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            closeResult = Result.failure(java.io.IOException("I/O error"))
        }

        // Act
        val result = ZetaSdkClientExtension.close(client)

        // Assert
        assertFalse(result)
    }

    @Test
    fun multipleOperations_canBeCalledSequentially() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            discoverResult = Result.success(Unit)
            registerResult = Result.success(Unit)
            authenticateResult = Result.success(Unit)
        }

        // Act
        val discoverResult = ZetaSdkClientExtension.discover(client)
        val registerResult = ZetaSdkClientExtension.register(client)
        val authenticateResult = ZetaSdkClientExtension.authenticate(client)

        // Assert
        assertTrue(discoverResult)
        assertTrue(registerResult)
        assertTrue(authenticateResult)
        assertTrue(client.discoverCalled)
        assertTrue(client.registerCalled)
        assertTrue(client.authenticateCalled)
    }

    @Test
    fun close_canBeCalledAfterOtherOperations() {
        // Arrange
        val client = FakeZetaSdkClient().apply {
            authenticateResult = Result.success(Unit)
            closeResult = Result.success(Unit)
        }

        // Act
        val authResult = ZetaSdkClientExtension.authenticate(client)
        val closeResult = ZetaSdkClientExtension.close(client)

        // Assert
        assertTrue(authResult)
        assertTrue(closeResult)
        assertTrue(client.authenticateCalled)
        assertTrue(client.closeCalled)
    }

    @Test
    fun logout_returnsTrue_whenClientLogoutSucceeds() {
        val client = FakeZetaSdkClient().apply {
            logoutResult = Result.success(Unit)
        }
        val result = ZetaSdkClientExtension.logout(client)
        assertTrue(result)
        assertTrue(client.logoutCalled)
    }

    @Test
    fun logout_returnsFalse_whenClientLogoutFails() {
        val client = FakeZetaSdkClient().apply {
            logoutResult = Result.failure(Exception("Logout failed"))
        }
        val result = ZetaSdkClientExtension.logout(client)
        assertFalse(result)
        assertTrue(client.logoutCalled)
    }

    @Test
    fun clearRegistration_returnsTrue_whenClientLogoutSucceeds() {
        val client = FakeZetaSdkClient().apply {
            logoutResult = Result.success(Unit)
        }
        val result = ZetaSdkClientExtension.clearRegistration(client)
        assertTrue(result)
        assertTrue(client.logoutCalled)
    }

    @Test
    fun clearRegistration_returnsFalse_whenClientLogoutFails() {
        val client = FakeZetaSdkClient().apply {
            logoutResult = Result.failure(Exception("Logout failed"))
        }
        val result = ZetaSdkClientExtension.clearRegistration(client)
        assertFalse(result)
        assertTrue(client.logoutCalled)
    }

    @Test
    fun status_returnsSuccess_whenClientStatusSucceeds() {
        val client = FakeZetaSdkClient().apply {
            statusResult = Result.success(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN)
        }
        val result = ZetaSdkClientExtension.status(client)

        assertEquals(SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN, result)
    }

    @Test
    fun status_returnsFailure_whenClientStatusFails() {
        val client = FakeZetaSdkClient().apply {
            statusResult = Result.failure(Exception("Status failed"))
        }

        assertFailsWith<Exception> {
            ZetaSdkClientExtension.status(client)
        }
    }

    @Test
    fun status_returnsAllPossibleStates() {
        SdkStatus.entries.forEach { expectedStatus ->
            val client = FakeZetaSdkClient().apply {
                statusResult = Result.success(expectedStatus)
            }
            val result = ZetaSdkClientExtension.status(client)
            assertEquals(expectedStatus, result)
        }
    }

    @Test
    fun changeEmail_returnsResponse_whenClientChangeEmailSucceeds() {
        val client = FakeZetaSdkClient().apply {
            changeEmailResult = Result.success(ChangeEmailResponse("verified"))
        }

        val result = ZetaSdkClientExtension.changeEmail(client, "new@example.de")

        assertEquals("verified", result.status)
        assertTrue(client.changeEmailCalled)
    }

    @Test
    fun changeEmail_throws_whenClientChangeEmailFails() {
        val client = FakeZetaSdkClient().apply {
            changeEmailResult = Result.failure(Exception("Email change failed"))
        }

        assertFailsWith<Exception> {
            ZetaSdkClientExtension.changeEmail(client, "new@example.de")
        }
        assertTrue(client.changeEmailCalled)
    }

    private class FakeZetaSdkClient : ZetaSdkClient {
        var discoverCalled = false
        var registerCalled = false
        var authenticateCalled = false
        var closeCalled = false

        var discoverResult: Result<Unit> = Result.success(Unit)
        var registerResult: Result<Unit> = Result.success(Unit)
        var authenticateResult: Result<Unit> = Result.success(Unit)
        var closeResult: Result<Unit> = Result.success(Unit)
        var logoutCalled = false
        var logoutResult: Result<Unit> = Result.success(Unit)
        var statusResult: Result<SdkStatus> = Result.success(SdkStatus.NOT_REGISTERED)
        var changeEmailCalled = false
        var changeEmailResult: Result<ChangeEmailResponse> = Result.success(ChangeEmailResponse("verified"))

        override suspend fun discover(): Result<Unit> {
            discoverCalled = true
            return discoverResult
        }

        override suspend fun register(): Result<Unit> {
            registerCalled = true
            return registerResult
        }

        override suspend fun authenticate(): Result<Unit> {
            authenticateCalled = true
            return authenticateResult
        }

        override suspend fun close(): Result<Unit> {
            closeCalled = true
            return closeResult
        }

        override suspend fun changeEmail(newEmail: String): Result<ChangeEmailResponse> {
            changeEmailCalled = true
            return changeEmailResult
        }

        override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
            throw NotImplementedError("Not needed for these tests")
        }

        override suspend fun <R> ws(targetUrl: String, builder: ZetaHttpClientBuilder.() -> Unit, customHeaders: Map<String, String>?, block: suspend DefaultClientWebSocketSession.() -> R) {}

        override suspend fun logout(): Result<Unit> {
            logoutCalled = true
            return logoutResult
        }

        override suspend fun status(): Result<SdkStatus> {
            return statusResult
        }
    }
}
