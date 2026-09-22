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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.sdk.crypto.CertificateRevokedException
import de.gematik.zeta.sdk.crypto.CrlValidity
import de.gematik.zeta.sdk.crypto.OcspRequestData
import de.gematik.zeta.sdk.crypto.OcspValidity
import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.network.http.client.CachedCrlResponse
import de.gematik.zeta.sdk.network.http.client.CachedOcspResponse
import de.gematik.zeta.sdk.network.http.client.DEFAULT_REVOCATION_CACHE_SECONDS
import de.gematik.zeta.sdk.network.http.client.MAX_CACHE_AGE_NO_NEXT_UPDATE_SECONDS
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.cacheKeyFor
import de.gematik.zeta.sdk.network.http.client.config.SecurityConfig
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.time.SystemZetaClock
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class RevocationCheckerTest {
    private val nowEpoch = Clock.System.now().epochSeconds
    private val certDer = byteArrayOf(1, 2, 3)
    private val issuerDer = byteArrayOf(4, 5, 6)
    private val ocspResponseBytes = ByteArray(32) { it.toByte() }
    private val crlBytes = ByteArray(32) { it.toByte() }
    private val stapleBytes = byteArrayOf(99, 98, 97)
    private val fixedClock = ZetaClock { Instant.fromEpochSeconds(nowEpoch) }

    @Test
    fun validate_usesStapledOcsp_whenProvided() = runTest {
        val handler = FakeRevocationHandler(nextUpdate = nowEpoch + 3_600L)
        val checker = buildChecker(handler = handler)

        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(1, handler.validateCallCount)
    }

    @Test
    fun validate_doesNotFetchDirectOcsp_whenStapleIsFresh() = runTest {
        val handler = FakeRevocationHandler(nextUpdate = nowEpoch + 3_600L)
        val checker = buildChecker(handler = handler)
        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(0, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_throwsAndDoesNotFallBack_whenStapleNextUpdateExpired() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3_600L,
            stapleBytes = stapleBytes,
            stapleThisUpdate = nowEpoch - (8 * 24 * 3600),
            stapleNextUpdate = nowEpoch - 3600,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = stapleBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }

        assertEquals(0, handler.prepareOcspRequestCallCount)
        assertEquals(0, handler.validateCrlCallCount)
    }

    @Test
    fun validate_throwsAndDoesNotFallBack_whenStapleTooOld() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3_600L,
            stapleBytes = stapleBytes,
            stapleThisUpdate = nowEpoch - (25 * 3600),
            stapleNextUpdate = null,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = stapleBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }

        assertEquals(0, handler.prepareOcspRequestCallCount)
        assertEquals(0, handler.validateCrlCallCount)
    }

    @Test
    fun validate_ignoresCachedOcsp_whenStapleExpired() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3_600L,
            stapleBytes = stapleBytes,
            stapleThisUpdate = nowEpoch - (8 * 24 * 3600),
            stapleNextUpdate = nowEpoch - 3600,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )
        assertEquals(1, handler.prepareOcspRequestCallCount)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = stapleBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }

        assertEquals(1, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_throwsAndDoesNotFallBack_whenStapleFailsValidation() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3_600L,
            stapleBytes = stapleBytes,
            stapleThisUpdate = nowEpoch,
            stapleNextUpdate = nowEpoch + 3_600L,
            stapleShouldFailValidation = true,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = stapleBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }

        assertEquals(0, handler.prepareOcspRequestCallCount)
        assertEquals(0, handler.validateCrlCallCount)
    }

    @Test
    fun validate_throws_whenStapleFailsValidation_evenIfAllowSkipForTestCertificatesIsEnabled() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + 3_600L,
            stapleBytes = stapleBytes,
            stapleThisUpdate = nowEpoch,
            stapleNextUpdate = nowEpoch + 3_600L,
            stapleShouldFailValidation = true,
        )
        val checker = buildChecker(
            handler = handler,
            clock = fixedClock,
            allowSkipForTestCertificates = true,
        )

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = stapleBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_throwsAndSkipsCrl_whenDirectOcspSaysRevoked() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspRevoked = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + 3_600L,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<CertificateRevokedException> {
            checker.validate(null, certDer, issuerDer)
        }

        // a signed REVOKED is an answer, not a failed attempt: no CRL may overturn it
        assertEquals(0, handler.validateCrlCallCount)
    }

    @Test
    fun validate_throws_whenCrlSaysRevoked() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + 3_600L,
            crlRevoked = true,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<CertificateRevokedException> {
            checker.validate(null, certDer, issuerDer)
        }
    }

    @Test
    fun validate_throwsAndDoesNotFallBack_whenStapleSaysRevoked() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspRevoked = true,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<CertificateRevokedException> {
            checker.validate(ocspResponseBytes, certDer, issuerDer)
        }

        assertEquals(0, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_skipsRevokedCertificate_whenAllowSkipForTestCertificatesIsEnabled() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspRevoked = true,
        )
        val checker = buildChecker(handler = handler, allowSkipForTestCertificates = true)

        checker.validate(ocspResponseBytes, certDer, issuerDer)
    }

    @Test
    fun validate_revalidatesStaple_onEveryCall_whenNoNextUpdate() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = null,
        )
        val checker = buildChecker(
            handler = handler,
            clock = fixedClock,
        )

        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        val countAfterFirst = handler.validateCallCount
        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )
        assertEquals(countAfterFirst + 1, handler.validateCallCount)
    }

    @Test
    fun validate_acceptsStaple_whenNoNextUpdate_withinTwentyFourHours() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (10 * 3600),
            nextUpdate = null,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        checker.validate(
            stapledOcspResponse = ocspResponseBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(1, handler.validateCallCount)
        assertEquals(0, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_usesStaple_whenOlderThanTheCacheDuration_becauseNextUpdateStillHolds() = runTest {
        val handler = FakeRevocationHandler(
            stapleBytes = stapleBytes,
            stapleThisUpdate = nowEpoch - (10 * 3_600L),
            stapleNextUpdate = nowEpoch + (10 * 24 * 3_600L),
        )
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = 3_600L,
            clock = fixedClock,
        )

        checker.validate(
            stapledOcspResponse = stapleBytes,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        // The cache duration is counted from a stored validatedAt. A staple has none:
        // it is validated on every handshake, so only its own nextUpdate bounds it.
        assertEquals(1, handler.validateCallCount)
        assertEquals(0, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_fetchesDirectOcsp_whenNoStapling() = runTest {
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val handler = FakeRevocationHandler(nextUpdate = nowEpoch + 3_600L)
        val httpClient = mockHttpClient(ocspResponse = ocspResponseBytes)
        val checker = buildChecker(
            handler = handler,
            httpClient = httpClient,
            storage = storage,
            resourceScope = resourceScope,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(1, handler.prepareOcspRequestCallCount)
        assertEquals(1, handler.validateCallCount)
    }

    @Test
    fun validate_usesDirectOcspCache_onSecondCall() = runTest {
        val handler = FakeRevocationHandler(nextUpdate = nowEpoch + 3_600L)
        val httpClient = mockHttpClient(ocspResponse = ocspResponseBytes)
        val checker = buildChecker(handler = handler, httpClient = httpClient, clock = fixedClock)

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )
        val prepareCountAfterFirst = handler.prepareOcspRequestCallCount

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(prepareCountAfterFirst, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_refetchesDirectOcsp_whenCacheExpired() = runTest {
        val handler = FakeRevocationHandler(nextUpdate = nowEpoch + 3_600L)
        val httpClient = mockHttpClient(ocspResponse = ocspResponseBytes)
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(storage = storage, resourceScope = resourceScope)

        revocationStorage.setOcsp(
            cacheKeyFor(certDer, issuerDer),
            CachedOcspResponse(
                responseDer = ocspResponseBytes,
                validatedAtEpochSeconds = nowEpoch - 3_601L,
                nextUpdateEpochSeconds = nowEpoch - 1L,
                thisUpdateEpochSeconds = nowEpoch - 3_601L,
            ),
        )

        val checker = buildChecker(
            handler = handler,
            httpClient = httpClient,
            storage = storage,
            resourceScope = resourceScope,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(1, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_cachesDirectOcsp_withNextUpdate() = runTest {
        val nextUpdate = nowEpoch + 3_600L
        val handler = FakeRevocationHandler(nextUpdate = nextUpdate)
        val httpClient = mockHttpClient(ocspResponse = ocspResponseBytes)
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(storage = storage, resourceScope = resourceScope)

        val checker = buildChecker(
            handler = handler,
            httpClient = httpClient,
            storage = storage,
            resourceScope = resourceScope,
            clock = fixedClock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        val cached = revocationStorage.getOcsp(cacheKeyFor(certDer, issuerDer))
        assertNotNull(cached)
        assertEquals(nextUpdate, cached.nextUpdateEpochSeconds)
        assertEquals(nowEpoch, cached.validatedAtEpochSeconds)
    }

    @Test
    fun validate_fallsBackToCrl_whenOcspFails() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + 3_600L,
        )
        val httpClient = mockHttpClient(crlResponse = crlBytes)
        val checker = buildChecker(handler = handler, httpClient = httpClient)

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(1, handler.validateCrlCallCount)
    }

    @Test
    fun validate_fallsBackToCrl_whenDirectOcspResponseExpired() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (8 * 24 * 3600),
            nextUpdate = nowEpoch - 3600,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + 3_600L,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(1, handler.prepareOcspRequestCallCount)
        assertEquals(0, handler.validateCallCount)
        assertEquals(1, handler.validateCrlCallCount)
    }

    @Test
    fun validate_fallsBackToCrl_whenDirectOcspResponseTooOld() = runTest {
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (25 * 3600),
            nextUpdate = null,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + 3_600L,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(1, handler.prepareOcspRequestCallCount)
        assertEquals(0, handler.validateCallCount)
        assertEquals(1, handler.validateCrlCallCount)
    }

    @Test
    fun validate_usesCrlCache_onSecondCall() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + 3_600L,
        )
        val httpClient = mockHttpClient(crlResponse = crlBytes)
        val checker = buildChecker(handler = handler, httpClient = httpClient, clock = fixedClock)

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )
        val validateCrlCountAfterFirst = handler.validateCrlCallCount

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        assertEquals(validateCrlCountAfterFirst, handler.validateCrlCallCount)
    }

    @Test
    fun validate_fails_whenNeitherOcspNorCrlAvailable() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = null,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_fails_whenOcspAndCrlBothFail() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlShouldFail = true,
        )
        val checker = buildChecker(handler = handler)

        assertFailsWith<IllegalStateException> {
            checker.validate(
                stapledOcspResponse = null,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_cachesDirectOcsp_cappedAtCacheDuration_whenNextUpdateIsFarAway() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + (10 * 24 * 3_600L),
        )
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(storage, resourceScope)
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = cacheDuration,
            storage = storage,
            resourceScope = resourceScope,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)

        val cached = revocationStorage.getOcsp(cacheKeyFor(certDer, issuerDer))
        assertNotNull(cached)
        assertEquals(nowEpoch, cached.validatedAtEpochSeconds)
        assertEquals(nowEpoch + (10 * 24 * 3_600L), cached.nextUpdateEpochSeconds)
        assertEquals(nowEpoch, cached.thisUpdateEpochSeconds)
    }

    @Test
    fun validate_refetchesDirectOcsp_afterCacheDurationElapsed_althoughNextUpdateIsFarAway() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + (10 * 24 * 3_600L),
        )
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = cacheDuration,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.prepareOcspRequestCallCount)

        clock.epochSeconds = nowEpoch + cacheDuration
        checker.validate(null, certDer, issuerDer)

        assertEquals(2, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_usesOcspCache_justBeforeCacheDurationElapses() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = nowEpoch + (10 * 24 * 3_600L),
        )
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = cacheDuration,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)

        clock.epochSeconds = nowEpoch + cacheDuration - 1L
        checker.validate(null, certDer, issuerDer)

        assertEquals(1, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_keepsOcspCachedBeyondCacheDuration_whenNextUpdateIsAbsent() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = null,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + MAX_CACHE_AGE_NO_NEXT_UPDATE_SECONDS + 3_600L,
            crlThisUpdate = nowEpoch,
        )
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = cacheDuration,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.prepareOcspRequestCallCount)

        // Still cached after the configured duration: the 24h spec fallback governs this branch.
        clock.epochSeconds = nowEpoch + cacheDuration
        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.prepareOcspRequestCallCount)

        clock.epochSeconds = nowEpoch + MAX_CACHE_AGE_NO_NEXT_UPDATE_SECONDS
        checker.validate(null, certDer, issuerDer)

        assertEquals(2, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_cachesDirectCrl_cappedAtCacheDuration_whenNextUpdateIsFarAway() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + (10 * 24 * 3_600L),
        )
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(storage, resourceScope)
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = cacheDuration,
            storage = storage,
            resourceScope = resourceScope,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)

        val cached = revocationStorage.getCrl(cacheKeyFor(certDer, issuerDer))
        assertNotNull(cached)
        assertEquals(nowEpoch, cached.validatedAtEpochSeconds)
        assertEquals(nowEpoch + (10 * 24 * 3_600L), cached.nextUpdateEpochSeconds)
    }

    @Test
    fun validate_refetchesCrl_afterCacheDurationElapsed_althoughNextUpdateIsFarAway() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + (10 * 24 * 3_600L),
        )
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = cacheDuration,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.validateCrlCallCount)

        clock.epochSeconds = nowEpoch + cacheDuration
        checker.validate(null, certDer, issuerDer)

        assertEquals(2, handler.validateCrlCallCount)
    }

    @Test
    fun validate_keepsCrlCachedBeyondCacheDuration_whenNextUpdateIsAbsent() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = null,
        )
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = cacheDuration,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)

        clock.epochSeconds = nowEpoch + cacheDuration
        checker.validate(null, certDer, issuerDer)

        assertEquals(1, handler.validateCrlCallCount)
    }

    @Test
    fun validate_cachesDirectOcsp_withStorageTimestamp() = runTest {
        val handler = FakeRevocationHandler(nextUpdate = nowEpoch + 3_600L)
        val httpClient = mockHttpClient(ocspResponse = ocspResponseBytes)
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(
            storage = storage,
            resourceScope = resourceScope,
        )

        val checker = buildChecker(
            handler = handler,
            httpClient = httpClient,
            storage = storage,
            resourceScope = resourceScope,
            clock = fixedClock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        val cached = revocationStorage.getOcsp(cacheKeyFor(certDer, issuerDer))
        assertNotNull(cached)
        assertEquals(nowEpoch, cached.validatedAtEpochSeconds)
    }

    @Test
    fun validate_cachesDirectCrl_withStorageTimestamp() = runTest {
        val fixedClock = ZetaClock { Instant.fromEpochSeconds(nowEpoch) }
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + 3_600L,
        )
        val httpClient = mockHttpClient(crlResponse = crlBytes)
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(
            storage = storage,
            resourceScope = resourceScope,
        )

        val checker = buildChecker(
            handler = handler,
            httpClient = httpClient,
            storage = storage,
            resourceScope = resourceScope,
            clock = fixedClock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        val cached = revocationStorage.getCrl(cacheKeyFor(certDer, issuerDer))
        assertNotNull(cached)
        assertEquals(nowEpoch, cached.validatedAtEpochSeconds)
    }

    @Test
    fun validate_cachesDirectCrl_withCrlThisUpdate_whenNoNextUpdate() = runTest {
        val handler = FakeRevocationHandler(
            nextUpdate = nowEpoch + 3_600L,
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = null,
            crlThisUpdate = nowEpoch - (5 * 3_600L),
        )
        val httpClient = mockHttpClient(crlResponse = crlBytes)
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(
            storage = storage,
            resourceScope = resourceScope,
        )

        val checker = buildChecker(
            handler = handler,
            httpClient = httpClient,
            storage = storage,
            resourceScope = resourceScope,
            clock = fixedClock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        val cached = revocationStorage.getCrl(cacheKeyFor(certDer, issuerDer))
        assertNotNull(cached)
        assertNull(cached.nextUpdateEpochSeconds)
        assertEquals(nowEpoch - (5 * 3_600L), cached.thisUpdateEpochSeconds)
        assertEquals(nowEpoch, cached.validatedAtEpochSeconds)
    }

    @Test
    fun validate_refetchesOcsp_afterNextUpdate_whenItIsSoonerThanTheCacheDuration() = runTest {
        val clock = MutableClock(nowEpoch)
        val handler = FakeRevocationHandler(thisUpdate = nowEpoch, nextUpdate = nowEpoch + 600L)
        val checker = buildChecker(handler = handler, cacheDurationSeconds = 3_600L, clock = clock)

        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.prepareOcspRequestCallCount)

        clock.epochSeconds = nowEpoch + 599L
        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.prepareOcspRequestCallCount)

        clock.epochSeconds = nowEpoch + 600L
        checker.validate(null, certDer, issuerDer)

        assertEquals(2, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_countsCacheDurationFromValidation_notFromThisUpdate() = runTest {
        val clock = MutableClock(nowEpoch)
        val cacheDuration = 3_600L
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (5 * 3_600L),
            nextUpdate = nowEpoch + (10 * 24 * 3_600L),
        )
        val checker = buildChecker(handler = handler, cacheDurationSeconds = cacheDuration, clock = clock)

        checker.validate(null, certDer, issuerDer)

        // counted from thisUpdate the entry would already be stale; it is counted from validation
        clock.epochSeconds = nowEpoch + cacheDuration - 1L
        checker.validate(null, certDer, issuerDer)

        assertEquals(1, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_expiresOcspCache_atThisUpdatePlusTwentyFourHours_whenNoNextUpdate() = runTest {
        val clock = MutableClock(nowEpoch)
        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (20 * 3_600L),
            nextUpdate = null,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = nowEpoch + (10 * 24 * 3_600L),
            crlThisUpdate = nowEpoch,
        )
        val checker = buildChecker(handler = handler, cacheDurationSeconds = 3_600L, clock = clock)

        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.prepareOcspRequestCallCount)

        // the window ends 24h after thisUpdate, i.e. 4h from now - not 24h from now
        clock.epochSeconds = nowEpoch + (4 * 3_600L) - 1L
        checker.validate(null, certDer, issuerDer)
        assertEquals(1, handler.prepareOcspRequestCallCount)

        clock.epochSeconds = nowEpoch + (4 * 3_600L)
        checker.validate(null, certDer, issuerDer)

        assertEquals(2, handler.prepareOcspRequestCallCount)
    }

    @Test
    fun validate_removesCachedOcsp_whenItIsNoLongerUsable() = runTest {
        val clock = MutableClock(nowEpoch)
        val storage = InMemoryStorage()
        val resourceScope = ResourceScope("https://resource.example.com", emptyList())
        val revocationStorage = RevocationStorage(storage = storage, resourceScope = resourceScope)
        val handler = FakeRevocationHandler(thisUpdate = nowEpoch, nextUpdate = nowEpoch + 600L)
        val checker = buildChecker(
            handler = handler,
            cacheDurationSeconds = 3_600L,
            storage = storage,
            resourceScope = resourceScope,
            clock = clock,
        )

        checker.validate(null, certDer, issuerDer)
        assertNotNull(revocationStorage.getOcsp(cacheKeyFor(certDer, issuerDer)))

        clock.epochSeconds = nowEpoch + 600L
        checker.validate(null, certDer, issuerDer)

        // storage no longer prunes itself: the checker drops what it rejects
        assertNull(revocationStorage.getOcsp(cacheKeyFor(certDer, issuerDer)))
    }

    @Test
    fun validate_rejectsCrl_whenNoNextUpdateAndOlderThanTwentyFourHours() = runTest {
        val handler = FakeRevocationHandler(
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = null,
            crlThisUpdate = nowEpoch - (25 * 3_600L),
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        assertFailsWith<IllegalStateException> {
            checker.validate(null, certDer, issuerDer)
        }

        assertEquals(0, handler.validateCrlCallCount)
    }

    @Test
    fun validate_acceptsCrl_whenNoNextUpdateAndWithinTwentyFourHours() = runTest {
        val handler = FakeRevocationHandler(
            ocspShouldFail = true,
            crlUrl = "http://crl.example.com/crl.crl",
            crlNextUpdate = null,
            crlThisUpdate = nowEpoch - (10 * 3_600L),
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        checker.validate(null, certDer, issuerDer)

        assertEquals(1, handler.validateCrlCallCount)
    }

    @Test
    fun defaultRevocationCacheSeconds_isOneHour() {
        assertEquals(3_600L, DEFAULT_REVOCATION_CACHE_SECONDS)
    }

    @Test
    fun revocationCacheDurationSeconds_defaultsToThirtySixHundred() {
        val builder = ZetaHttpClientBuilder()
        assertEquals(3_600L, builder.revocationCacheDurationSeconds)
    }

    @Test
    fun revocationCacheDuration_updatesSecurityConfig() {
        val builder = ZetaHttpClientBuilder()
            .revocationCacheDuration(7_200L)

        assertEquals(7_200L, builder.revocationCacheDurationSeconds)
    }

    @Test
    fun revocationCacheDuration_returnsSameBuilderInstance() {
        val builder = ZetaHttpClientBuilder()
        val result = builder.revocationCacheDuration(7_200L)

        assertSame(builder, result)
    }

    @Test
    fun revocationCacheDuration_lastCallWins_whenCalledMultipleTimes() {
        val builder = ZetaHttpClientBuilder()
            .revocationCacheDuration(7_200L)
            .revocationCacheDuration(10_800L)

        assertEquals(10_800L, builder.revocationCacheDurationSeconds)
    }

    @Test
    fun revocationCacheDuration_atExactMinimum_isAccepted() {
        val builder = ZetaHttpClientBuilder()
            .revocationCacheDuration(3_600L)

        assertEquals(3_600L, builder.revocationCacheDurationSeconds)
    }

    @Test
    fun revocationCacheDuration_belowMinimum_throws() {
        val builder = ZetaHttpClientBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.revocationCacheDuration(3_599L)
        }
    }

    @Test
    fun revocationCacheDuration_negative_throws() {
        val builder = ZetaHttpClientBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.revocationCacheDuration(-1L)
        }
    }

    @Test
    fun defaultRevocationCacheDurationSeconds_isThirtySixHundred() {
        val config = SecurityConfig()
        assertEquals(3_600L, config.revocationCacheDurationSeconds)
    }

    @Test
    fun revocationCacheDurationSeconds_atExactMinimum_isAccepted() {
        val config = SecurityConfig(revocationCacheDurationSeconds = 3_600L)
        assertEquals(3_600L, config.revocationCacheDurationSeconds)
    }

    @Test
    fun revocationCacheDurationSeconds_aboveMinimum_isAccepted() {
        val config = SecurityConfig(revocationCacheDurationSeconds = 86_400L)
        assertEquals(86_400L, config.revocationCacheDurationSeconds)
    }

    @Test
    fun revocationCacheDurationSeconds_belowMinimum_throws() {
        val exception = assertFailsWith<IllegalArgumentException> {
            SecurityConfig(revocationCacheDurationSeconds = 3_599L)
        }
        assertTrue(exception.message!!.contains("3600"))
    }

    @Test
    fun revocationCacheDurationSeconds_zero_throws() {
        assertFailsWith<IllegalArgumentException> {
            SecurityConfig(revocationCacheDurationSeconds = 0L)
        }
    }

    @Test
    fun revocationCacheDurationSeconds_negative_throws() {
        assertFailsWith<IllegalArgumentException> {
            SecurityConfig(revocationCacheDurationSeconds = -3_600L)
        }
    }

    @Test
    fun copy_withNewRevocationCacheDuration_preservesOtherFields() {
        val original = SecurityConfig(
            additionalCaPem = listOf("cert-pem"),
            additionalCaFile = "/path/to/ca.pem",
            disableServerValidation = true,
            sslVerbose = true,
        )

        val updated = original.copy(revocationCacheDurationSeconds = 10_800L)

        assertEquals(10_800L, updated.revocationCacheDurationSeconds)
        assertEquals(original.additionalCaPem, updated.additionalCaPem)
        assertEquals(original.additionalCaFile, updated.additionalCaFile)
        assertEquals(original.disableServerValidation, updated.disableServerValidation)
        assertEquals(original.sslVerbose, updated.sslVerbose)
    }

    private class MutableClock(var epochSeconds: Long) : ZetaClock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
    }

    private fun buildChecker(
        handler: RevocationHandler = FakeRevocationHandler(),
        httpClient: ZetaHttpClient = mockHttpClient(),
        cacheDurationSeconds: Long = 3_600L,
        allowSkipForTestCertificates: Boolean = false,
        storage: SdkStorage = InMemoryStorage(),
        resourceScope: ResourceScope = ResourceScope("https://resource.example.com", emptyList()),
        clock: ZetaClock = SystemZetaClock,
    ): RevocationChecker = RevocationChecker(
        storage = RevocationStorage(storage = storage, resourceScope = resourceScope),
        httpClient = httpClient,
        handler = handler,
        cacheDurationSeconds = cacheDurationSeconds,
        allowSkipForTestCertificates = allowSkipForTestCertificates,
        clock = clock,
    )

    private fun mockHttpClient(
        ocspResponse: ByteArray = ocspResponseBytes,
        crlResponse: ByteArray = crlBytes,
    ): ZetaHttpClient = ZetaHttpClient(
        HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString().contains("ocsp") -> respond(
                        content = ocspResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/ocsp-response",
                        ),
                    )
                    request.url.toString().contains("crl") -> respond(
                        content = crlResponse,
                        status = HttpStatusCode.OK,
                    )
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ),
    )

    private class FakeRevocationHandler(
        private val thisUpdate: Long = Clock.System.now().epochSeconds,
        private val nextUpdate: Long? = Clock.System.now().epochSeconds + 3_600L,
        private val ocspShouldFail: Boolean = false,
        private val crlUrl: String? = "http://crl.example.com/crl.crl",
        private val crlNextUpdate: Long? = Clock.System.now().epochSeconds + 3_600L,
        private val crlShouldFail: Boolean = false,
        private val crlThisUpdate: Long = Clock.System.now().epochSeconds,
        private val ocspRevoked: Boolean = false,
        private val stapleShouldFailValidation: Boolean = false,
        private val crlRevoked: Boolean = false,
        private val stapleBytes: ByteArray? = null,
        private val stapleThisUpdate: Long = Clock.System.now().epochSeconds,
        private val stapleNextUpdate: Long? = Clock.System.now().epochSeconds + 3_600L,
    ) : RevocationHandler {

        var validateCallCount = 0
        var validateCrlCallCount = 0
        var prepareOcspRequestCallCount = 0

        private fun isStaple(ocspResponseDer: ByteArray): Boolean =
            stapleBytes != null && ocspResponseDer.contentEquals(stapleBytes)

        override fun getOcspValidity(
            ocspResponseDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
        ): OcspValidity = if (isStaple(ocspResponseDer)) {
            OcspValidity(stapleThisUpdate, stapleNextUpdate)
        } else {
            OcspValidity(thisUpdate, nextUpdate)
        }

        override fun validate(
            ocspResponseDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
            now: Instant,
        ) {
            validateCallCount++
            if (ocspRevoked) throw CertificateRevokedException("Certificate is REVOKED")
            if (stapleShouldFailValidation && isStaple(ocspResponseDer)) {
                error("OCSP response signature invalid")
            }
            if (ocspShouldFail) error("OCSP validation failed")
        }

        override fun validateCrl(
            crlDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
            now: Instant,
        ) {
            validateCrlCallCount++
            if (crlRevoked) throw CertificateRevokedException("Certificate is REVOKED")
            if (crlShouldFail) error("CRL validation failed")
        }

        override fun extractCrlUrl(certDer: ByteArray): String? = crlUrl

        override fun getCrlValidity(crlDer: ByteArray): CrlValidity =
            CrlValidity(crlThisUpdate, crlNextUpdate)

        override suspend fun prepareOcspRequest(
            certDer: ByteArray,
            issuerDer: ByteArray,
        ): OcspRequestData {
            prepareOcspRequestCallCount++
            if (ocspShouldFail) error("OCSP request failed")
            return OcspRequestData(
                url = "http://ocsp.example.com",
                requestDer = byteArrayOf(),
            )
        }
    }
}
