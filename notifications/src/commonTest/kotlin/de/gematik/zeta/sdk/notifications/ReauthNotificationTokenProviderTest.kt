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

package de.gematik.zeta.sdk.notifications

import Jwk
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReauthNotificationTokenProviderTest {

    private val nsRequest = NotificationTokenRequest(
        audience = "https://ns.example.com",
        scopes = setOf("notification.pusher.write", "notification.channel.read"),
    )

    @Test
    fun getAccessToken_issuesWithNsAudienceAndSortedScopes() = runTest {
        // Arrange
        val (sut, issuer) = buildSut()

        // Act
        val token = sut.getAccessToken(NotificationScopes.ALL)

        // Assert - full issuance ran once with the NS audience and the sorted NS scopes
        assertEquals("ns_token", token)
        assertEquals(1, issuer.issueCount)
        val params = issuer.paramsSeen.single()
        assertEquals("https://ns.example.com", params.audience)
        assertEquals(listOf("notification.channel.read", "notification.pusher.write"), params.scopes)
    }

    @Test
    fun getAccessToken_returnsCachedToken_onSecondCall() = runTest {
        // Arrange
        val (sut, issuer) = buildSut()

        // Act - the isolated store caches the token, so getValidToken re-issues only once
        sut.getAccessToken(NotificationScopes.ALL)
        val second = sut.getAccessToken(NotificationScopes.ALL)

        // Assert
        assertEquals("ns_token", second)
        assertEquals(1, issuer.issueCount)
    }

    @Test
    fun invalidate_dropsCache_forcingReissue() = runTest {
        // Arrange
        val (sut, issuer) = buildSut()
        sut.getAccessToken(NotificationScopes.ALL)

        // Act
        sut.invalidate(NotificationScopes.ALL)
        sut.getAccessToken(NotificationScopes.ALL)

        // Assert
        assertEquals(2, issuer.issueCount)
    }

    @Test
    fun getAccessToken_stepsUpAndRetriesOnce_onRecoverableFailure() = runTest {
        // Arrange - the first issuance fails recoverably (dead session), step-up re-establishes it
        val (sut, issuer, stepUp) = buildSutWithStepUp()
        issuer.throwOnIssue.add(RecoverableAuthenticationException(null, "invalid_grant"))

        // Act
        val token = sut.getAccessToken(NotificationScopes.ALL)

        // Assert - one failed attempt, one step-up, one successful retry
        assertEquals("ns_token", token)
        assertEquals(1, stepUp.count)
        assertEquals(2, issuer.attempts)
        assertEquals(1, issuer.issueCount)
    }

    @Test
    fun getAccessToken_propagates_whenRetryAfterStepUpFailsAgain() = runTest {
        // Arrange
        val (sut, issuer, stepUp) = buildSutWithStepUp()
        issuer.throwOnIssue.add(RecoverableAuthenticationException(null, "invalid_grant"))
        issuer.throwOnIssue.add(RecoverableAuthenticationException(null, "invalid_grant"))

        // Act / Assert - exactly one step-up, no endless retry loop
        assertFailsWith<RecoverableAuthenticationException> { sut.getAccessToken(NotificationScopes.ALL) }
        assertEquals(1, stepUp.count)
        assertEquals(2, issuer.attempts)
    }

    @Test
    fun concurrentGetAccessToken_issuesOnce() = runTest {
        // Arrange
        val (sut, issuer) = buildSut()
        issuer.delayMs = 100

        // Act - the second caller waits on the mutex and hits the fresh cache entry
        val results = listOf(
            async { sut.getAccessToken(NotificationScopes.ALL) },
            async { sut.getAccessToken(NotificationScopes.ALL) },
        ).awaitAll()

        // Assert
        assertEquals(1, issuer.issueCount)
        assertTrue(results.all { it == "ns_token" })
    }

    // ---- NotificationTokenStorage ----

    @Test
    fun store_roundTripsAccessTokenAndExpiry_butNeverRefreshToken() = runTest {
        // Arrange
        val store = NotificationTokenStorage()

        // Act
        store.saveAccessTokens("ns_token", "unused_refresh", 4242L)

        // Assert - refresh token is dropped so getValidToken always full-issues on expiry
        assertEquals("ns_token", store.getAccessToken())
        assertEquals("4242", store.getTokenExpiration())
        assertNull(store.getRefreshToken())
    }

    @Test
    fun store_clear_removesToken() = runTest {
        // Arrange
        val store = NotificationTokenStorage()
        store.saveAccessTokens("ns_token", "unused_refresh", 4242L)

        // Act
        store.clear()

        // Assert
        assertNull(store.getAccessToken())
        assertNull(store.getTokenExpiration())
    }

    // ---- fixtures ----

    private class StepUpHolder {
        var count = 0
        suspend fun invoke() {
            count++
        }
    }

    /**
     * Fake [AccessTokenProvider] mirroring the real `getValidToken` contract the provider relies on:
     * a cache hit (token in [store], beyond the 10 s safety margin) returns without issuing, expiry
     * forces a fresh issuance, and the issued token is persisted through [store]. Records the params
     * it was issued with so the audience/scope override can be asserted.
     */
    private class FakeIssuer(
        private val store: AuthenticationStorage,
        private val clock: () -> Long = { 1000L },
    ) : AccessTokenProvider {
        var issueCount = 0
        var attempts = 0
        var delayMs: Long = 0
        val paramsSeen = mutableListOf<AccessTokenParams>()
        val throwOnIssue = ArrayDeque<Exception>()

        override suspend fun getValidToken(tokenEndpoint: String, nonceEndpoint: String, params: AccessTokenParams, dpopKey: String): String {
            val cached = store.getAccessToken()
            val exp = store.getTokenExpiration()?.toLongOrNull()
            if (cached != null && exp != null && exp - clock() > 10) return cached

            if (delayMs > 0) delay(delayMs)
            attempts++
            if (throwOnIssue.isNotEmpty()) throw throwOnIssue.removeFirst()
            issueCount++
            paramsSeen += params
            store.saveAccessTokens("ns_token", "unused_refresh", clock() + 3600)
            return "ns_token"
        }

        override suspend fun createDpopToken(dpopKey: Jwk, method: String, url: String, nonceBytes: ByteArray?, accessTokenHash: String?): String = ""
        override suspend fun hash(token: String): String = ""
    }

    private val baseParams = AccessTokenParams(
        clientId = "client_id",
        productId = "product_id",
        productVersion = "1.0.0",
        expiration = 300L,
        scopes = listOf("placeholder"),
        audience = "https://placeholder.example.com",
        platformProductId = PlatformProductId.LinuxProductId("linux", "linux", "linux", "linux"),
    )

    // The provider owns its internal store and hands it to the issuerFactory; the fake issuer must
    // read/write that same store, so it is captured from the factory lambda.
    private fun buildProvider(stepUp: suspend () -> Unit = {}): Pair<ReauthNotificationTokenProvider, FakeIssuer> {
        lateinit var issuer: FakeIssuer
        val provider = ReauthNotificationTokenProvider(
            issuerFactory = { store -> FakeIssuer(store).also { issuer = it } },
            endpoints = { NotificationTokenEndpoints("https://token.endpoint", "https://nonce.endpoint") },
            baseParams = { baseParams },
            dpopKid = { "fake-kid" },
            stepUp = stepUp,
            resolveRequest = { nsRequest },
        )
        return provider to issuer
    }

    private fun buildSut(): Pair<ReauthNotificationTokenProvider, FakeIssuer> = buildProvider()

    private fun buildSutWithStepUp(): Triple<ReauthNotificationTokenProvider, FakeIssuer, StepUpHolder> {
        val holder = StepUpHolder()
        val (provider, issuer) = buildProvider(stepUp = { holder.invoke() })
        return Triple(provider, issuer, holder)
    }
}
