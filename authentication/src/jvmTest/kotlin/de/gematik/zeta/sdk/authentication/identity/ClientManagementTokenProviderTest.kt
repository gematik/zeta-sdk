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

package de.gematik.zeta.sdk.authentication.identity

import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import de.gematik.zeta.sdk.authentication.identity.models.ClientManagementScopes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClientManagementTokenProviderTest {
    private val cm = ClientManagementScopes.CLIENT_MANAGEMENT
    private val endpoints = ClientManagementTokenEndpoints("https://guard.example.com/token", "https://guard.example.com/nonce")
    private val audience = "https://guard.example.com"
    private val dpopKid = "dpop-kid"

    private val issuer = mockk<AccessTokenProvider>()
    private val baseParams = mockk<AccessTokenParams>()
    private val issuedParams = mockk<AccessTokenParams>()
    private val storage = FakeAuthenticationStorage()

    private var stepUps = 0
    private var storeSeenByFactory: AuthenticationStorage? = null

    @BeforeTest
    fun setUp() {
        every { baseParams.copy(scopes = listOf(cm), audience = audience) } returns issuedParams
    }

    @Test
    fun getAccessToken_issuesTokenForClientManagementScopeAndAudience() = runTest {
        coEvery { issuer.getValidToken(endpoints.tokenEndpoint, endpoints.nonceEndpoint, issuedParams, dpopKid) } returns "at-1"

        val token = provider().getAccessToken(setOf(cm))

        assertEquals("at-1", token)
        verify(exactly = 1) { baseParams.copy(scopes = listOf(cm), audience = audience) }
        assertEquals(0, stepUps)
    }

    @Test
    fun getAccessToken_rejectsScopesBeyondClientManagement() = runTest {
        assertFailsWith<IllegalArgumentException> { provider().getAccessToken(setOf(cm, "openid")) }

        coVerify(exactly = 0) { issuer.getValidToken(any(), any(), any(), any()) }
    }

    @Test
    fun getAccessToken_stepsUpOnceAndRetries_onRecoverableFailure() = runTest {
        coEvery { issuer.getValidToken(any(), any(), any(), any()) } throws recoverable() andThen "at-2"

        val token = provider().getAccessToken(setOf(cm))

        assertEquals("at-2", token)
        assertEquals(1, stepUps)
        coVerify(exactly = 2) { issuer.getValidToken(any(), any(), any(), any()) }
    }

    @Test
    fun getAccessToken_propagatesSecondRecoverableFailure_withoutSecondStepUp() = runTest {
        coEvery { issuer.getValidToken(any(), any(), any(), any()) } throws recoverable()

        assertFailsWith<RecoverableAuthenticationException> { provider().getAccessToken(setOf(cm)) }

        assertEquals(1, stepUps)
    }

    @Test
    fun getAccessToken_doesNotStepUp_onNonRecoverableFailure() = runTest {
        coEvery { issuer.getValidToken(any(), any(), any(), any()) } throws IllegalStateException("token endpoint down")

        assertFailsWith<IllegalStateException> { provider().getAccessToken(setOf(cm)) }

        assertEquals(0, stepUps)
    }

    @Test
    fun getAccessToken_propagatesStepUpFailure() = runTest {
        coEvery { issuer.getValidToken(any(), any(), any(), any()) } throws recoverable()
        val provider = provider(stepUp = { error("user cancelled step-up") })

        val e = assertFailsWith<IllegalStateException> { provider.getAccessToken(setOf(cm)) }

        assertEquals("user cancelled step-up", e.message)
        coVerify(exactly = 1) { issuer.getValidToken(any(), any(), any(), any()) }
    }

    @Test
    fun invalidate_clearsOnlyTheAccessToken() = runTest {
        storage.accessToken = "at"

        provider().invalidate(setOf(cm))

        assertNull(storage.accessToken)
        assertEquals(1, storage.accessTokenClears)
        assertEquals(0, storage.clears)
    }

    @Test
    fun clear_clearsTheWholeStore() = runTest {
        storage.accessToken = "at"

        provider().clear()

        assertEquals(1, storage.clears)
    }

    @Test
    fun issuerFactory_receivesStoreThatNeverPersistsRefreshTokens() = runTest {
        provider()
        val store = assertNotNull(storeSeenByFactory)

        store.saveAccessTokens("at", "rt", expiresAt = 100, refreshExpiresAt = 200)

        assertEquals("at", storage.accessToken)
        assertEquals("", storage.refreshToken)
        assertNull(store.getRefreshToken())
    }

    private fun provider(stepUp: suspend () -> Unit = { stepUps++ }) = ClientManagementTokenProvider(
        issuerFactory = { store ->
            storeSeenByFactory = store
            issuer
        },
        persistentStorage = storage,
        endpoints = { endpoints },
        baseParams = { baseParams },
        dpopKid = { dpopKid },
        stepUp = stepUp,
        audience = { audience },
    )

    private fun recoverable(): RecoverableAuthenticationException = mockk(relaxed = true)
}
