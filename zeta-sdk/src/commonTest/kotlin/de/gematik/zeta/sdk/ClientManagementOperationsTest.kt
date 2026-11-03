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

import de.gematik.zeta.sdk.authentication.identity.ChallengeType
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.identity.ClientManagement
import de.gematik.zeta.sdk.authentication.identity.ClientManagementApiClient
import de.gematik.zeta.sdk.authentication.identity.EmailChallenge
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientManagementOperationsTest {

    private val ownId = "own-client"
    private val otherId = "other-client"

    private val calls = mutableListOf<String>()
    private val api = FakeClientManagementApi(calls)

    private var registered = true
    private var localClientId: String? = ownId
    private var discoveryError: Throwable? = null
    private var clearRegistrationError: Throwable? = null
    private var clientManagementCreations = 0

    private val ops = ClientManagementOperations(
        discover = {
            calls += "discover"
            discoveryError?.let { throw it }
        },
        isRegistered = {
            calls += "isRegistered"
            registered
        },
        currentClientId = { localClientId },
        clearRegistration = {
            calls += "clearRegistration"
            clearRegistrationError?.let { throw it }
        },
        clientManagement = {
            clientManagementCreations++
            ClientManagement(api)
        },
    )

    @Test
    fun listClients_discoversAndChecksRegistrationBeforeCallingApi() = runTest {
        api.clients = listOf(RegisteredClient(id = ownId, name = "Pixel 9"))

        val clients = ops.listClients()

        assertEquals(listOf(RegisteredClient(id = ownId, name = "Pixel 9")), clients)
        assertEquals(listOf("discover", "isRegistered", "api.listClients"), calls)
    }

    @Test
    fun listClients_failsWithoutCallingApi_whenNotRegistered() = runTest {
        registered = false

        val e = assertFailsWith<IllegalStateException> { ops.listClients() }

        assertEquals(ClientManagementOperations.NOT_REGISTERED, e.message)
        assertEquals(listOf("discover", "isRegistered"), calls)
        assertEquals(0, clientManagementCreations)
    }

    @Test
    fun listClients_propagatesDiscoveryFailure_beforeCheckingRegistration() = runTest {
        val failure = IllegalStateException("discovery failed")
        discoveryError = failure

        val e = assertFailsWith<IllegalStateException> { ops.listClients() }

        assertSame(failure, e)
        assertEquals(listOf("discover"), calls)
        assertEquals(0, clientManagementCreations)
    }

    @Test
    fun changeEmail_delegates_whenRegistered() = runTest {
        api.changeEmailResponse = ChangeEmailResponse.Pending(challengeType = ChallengeType.EMAIL_OTP, emailHint = "n***@example.de")

        val response = ops.changeEmail("new@example.com")

        assertEquals(ChangeEmailResponse.Pending(challengeType = ChallengeType.EMAIL_OTP, emailHint = "n***@example.de"), response)
        assertEquals(listOf("discover", "isRegistered", "api.changeEmail:new@example.com"), calls)
    }

    @Test
    fun changeEmail_failsWithoutCallingApi_whenNotRegistered() = runTest {
        registered = false

        assertFailsWith<IllegalStateException> { ops.changeEmail("new@example.com") }

        assertTrue(calls.none { it.startsWith("api.") })
    }

    @Test
    fun resendEmailChangeCode_delegates_whenRegistered() = runTest {
        val challenge = ops.resendEmailChangeCode()

        assertEquals(EmailChallenge(challengeType = ChallengeType.EMAIL_OTP), challenge)
        assertEquals(listOf("discover", "isRegistered", "api.resendEmailChangeCode"), calls)
    }

    @Test
    fun verifyEmailChange_delegates_whenRegistered() = runTest {
        ops.verifyEmailChange("123456")

        assertEquals(listOf("discover", "isRegistered", "api.verifyEmailChange:123456"), calls)
    }

    @Test
    fun verifyEmailChange_failsWithoutCallingApi_whenNotRegistered() = runTest {
        registered = false

        assertFailsWith<IllegalStateException> { ops.verifyEmailChange("123456") }

        assertTrue(calls.none { it.startsWith("api.") })
    }

    @Test
    fun deleteClient_rejectsBlankId_beforeDiscovery() = runTest {
        assertFailsWith<IllegalArgumentException> { ops.deleteClient(" ") }

        assertTrue(calls.isEmpty())
    }

    @Test
    fun deleteClient_failsWithoutCallingApi_whenNotRegistered() = runTest {
        registered = false

        assertFailsWith<IllegalStateException> { ops.deleteClient(otherId) }

        assertTrue(calls.none { it.startsWith("api.") })
    }

    @Test
    fun deleteOwnClient_deleted_clearsLocalRegistration() = runTest {
        ops.deleteClient(ownId)

        assertEquals(listOf("discover", "isRegistered", "api.deleteClient:$ownId", "clearRegistration"), calls)
    }

    @Test
    fun deleteOtherClient_deleted_keepsLocalRegistration() = runTest {
        ops.deleteClient(otherId)

        assertTrue("clearRegistration" !in calls)
    }

    @Test
    fun deleteClient_withoutLocalClientId_neverClearsRegistration() = runTest {
        localClientId = null

        ops.deleteClient(ownId)

        assertTrue("clearRegistration" !in calls)
    }

    @Test
    fun deleteOwnClient_apiFailure_keepsLocalRegistration() = runTest {
        api.deleteError = IllegalStateException("server error")

        assertFailsWith<IllegalStateException> { ops.deleteClient(ownId) }

        assertTrue("clearRegistration" !in calls)
    }

    @Test
    fun deleteOwnClient_propagatesClearRegistrationFailure() = runTest {
        clearRegistrationError = IllegalStateException("storage failure")

        assertFailsWith<IllegalStateException> { ops.deleteClient(ownId) }

        assertTrue("api.deleteClient:$ownId" in calls)
    }

    private class FakeClientManagementApi(private val calls: MutableList<String>) : ClientManagementApiClient {
        var clients: List<RegisteredClient> = emptyList()
        var deleteError: Throwable? = null
        var changeEmailResponse: ChangeEmailResponse = ChangeEmailResponse.Verified

        override suspend fun listClients(): List<RegisteredClient> {
            calls += "api.listClients"
            return clients
        }

        override suspend fun deleteClient(id: String) {
            calls += "api.deleteClient:$id"
            deleteError?.let { throw it }
        }

        override suspend fun changeEmail(newEmail: String): ChangeEmailResponse {
            calls += "api.changeEmail:$newEmail"
            return changeEmailResponse
        }

        override suspend fun resendEmailChangeCode(): EmailChallenge {
            calls += "api.resendEmailChangeCode"
            return EmailChallenge(challengeType = ChallengeType.EMAIL_OTP)
        }

        override suspend fun verifyEmailChange(code: String) {
            calls += "api.verifyEmailChange:$code"
        }
    }
}
