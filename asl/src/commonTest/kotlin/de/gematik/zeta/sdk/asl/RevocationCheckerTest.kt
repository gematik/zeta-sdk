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

import de.gematik.zeta.sdk.crypto.OcspRequestData
import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.network.http.client.CachedOcspResponse
import de.gematik.zeta.sdk.network.http.client.DEFAULT_MIN_REVOCATION_CACHE_SECONDS
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.cacheKeyFor
import de.gematik.zeta.sdk.network.http.client.config.SecurityConfig
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
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
    fun validate_cachesStapledOcsp_afterFirstValidation() = runTest {
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
    fun validate_rejectsStaple_whenNextUpdateExpired() = runTest {
        val fixedClock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(nowEpoch)
        }

        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (8 * 24 * 3600),
            nextUpdate = nowEpoch - 3600,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        assertFailsWith<IllegalArgumentException> {
            checker.validate(
                stapledOcspResponse = ocspResponseBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_fails_whenStapledOcspTooOld() = runTest {
        val fixedClock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(nowEpoch)
        }

        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch - (25 * 3600),
            nextUpdate = null,
        )
        val checker = buildChecker(handler = handler, clock = fixedClock)

        assertFailsWith<IllegalArgumentException> {
            checker.validate(
                stapledOcspResponse = ocspResponseBytes,
                certDer = certDer,
                issuerDer = issuerDer,
            )
        }
    }

    @Test
    fun validate_stapledOcsp_withNoNextUpdate_cachesForMinDuration() = runTest {
        val fixedClock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(nowEpoch)
        }

        val handler = FakeRevocationHandler(
            thisUpdate = nowEpoch,
            nextUpdate = null,
        )
        val minCache = 3_600L
        val checker = buildChecker(
            handler = handler,
            minOcspCacheDurationSeconds = minCache,
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
        val fixedClock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(nowEpoch)
        }
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
                expiresAtEpochSeconds = nowEpoch - 1L,
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
        val fixedClock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(nowEpoch)
        }
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
        assertEquals(nextUpdate, cached.expiresAtEpochSeconds)
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
    fun validate_usesCrlCache_onSecondCall() = runTest {
        val fixedClock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(nowEpoch)
        }
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
    fun resolveExpiresAt_usesNextUpdate_whenPresent() {
        val nextUpdate = nowEpoch + 100L
        val checker = buildChecker(minOcspCacheDurationSeconds = 3_600L)

        assertEquals(nextUpdate, checker.resolveExpiresAt(nextUpdate))
    }

    @Test
    fun resolveExpiresAt_usesMinDuration_whenNextUpdateNull() {
        val minCache = 3_600L
        val checker = buildChecker(minOcspCacheDurationSeconds = minCache)
        val before = Clock.System.now().epochSeconds

        val expiresAt = checker.resolveExpiresAt(null)

        val after = Clock.System.now().epochSeconds
        assertTrue(expiresAt >= before + minCache)
        assertTrue(expiresAt <= after + minCache)
    }

    @Test
    fun resolveExpiresAt_nextUpdateTakesPrecedence_overMinDuration() {
        val nextUpdate = nowEpoch + 100L // shorter than 1h
        val checker = buildChecker(minOcspCacheDurationSeconds = 3_600L)

        assertEquals(nextUpdate, checker.resolveExpiresAt(nextUpdate))
    }

    @Test
    fun defaultMinRevocationCacheSeconds_isOneHour() {
        assertEquals(3_600L, DEFAULT_MIN_REVOCATION_CACHE_SECONDS)
    }

    @Test
    fun revocationCacheMinDurationSeconds_defaultsToThirtySixHundred() {
        val builder = ZetaHttpClientBuilder()
        assertEquals(3_600L, builder.revocationCacheMinDurationSeconds)
    }

    @Test
    fun revocationCacheDuration_updatesSecurityConfig() {
        val builder = ZetaHttpClientBuilder()
            .revocationCacheDuration(7_200L)

        assertEquals(7_200L, builder.revocationCacheMinDurationSeconds)
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

        assertEquals(10_800L, builder.revocationCacheMinDurationSeconds)
    }

    @Test
    fun revocationCacheDuration_atExactMinimum_isAccepted() {
        val builder = ZetaHttpClientBuilder()
            .revocationCacheDuration(3_600L)

        assertEquals(3_600L, builder.revocationCacheMinDurationSeconds)
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

    private fun buildChecker(
        handler: RevocationHandler = FakeRevocationHandler(),
        httpClient: HttpClient = mockHttpClient(),
        minOcspCacheDurationSeconds: Long = 3_600L,
        allowSkipForTestCertificates: Boolean = false,
        storage: SdkStorage = InMemoryStorage(),
        resourceScope: ResourceScope = ResourceScope("https://resource.example.com", emptyList()),
        clock: Clock = Clock.System,
    ): RevocationChecker = RevocationChecker(
        storage = RevocationStorage(storage = storage, resourceScope = resourceScope, clock = clock),
        httpClient = httpClient,
        handler = handler,
        cacheDurationSeconds = minOcspCacheDurationSeconds,
        allowSkipForTestCertificates = allowSkipForTestCertificates,
        clock = clock,
    )

    private fun mockHttpClient(
        ocspResponse: ByteArray = ocspResponseBytes,
        crlResponse: ByteArray = crlBytes,
    ): HttpClient = HttpClient(
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
    )

    private class FakeRevocationHandler(
        private val thisUpdate: Long = Clock.System.now().epochSeconds,
        private val nextUpdate: Long? = Clock.System.now().epochSeconds + 3_600L,
        private val ocspShouldFail: Boolean = false,
        private val crlUrl: String? = "http://crl.example.com/crl.crl",
        private val crlNextUpdate: Long? = Clock.System.now().epochSeconds + 3_600L,
        private val crlShouldFail: Boolean = false,
    ) : RevocationHandler {

        var validateCallCount = 0
        var validateCrlCallCount = 0
        var prepareOcspRequestCallCount = 0

        override fun getThisUpdateEpochSeconds(ocspResponseDer: ByteArray): Long = thisUpdate

        override fun getNextUpdateEpochSeconds(
            ocspResponseDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
        ): Long? = nextUpdate

        override fun validate(
            ocspResponseDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
        ) {
            validateCallCount++
            if (ocspShouldFail) error("OCSP validation failed")
        }

        override fun validateCrl(
            crlDer: ByteArray,
            certDer: ByteArray,
            issuerDer: ByteArray,
        ) {
            validateCrlCallCount++
            if (crlShouldFail) error("CRL validation failed")
        }

        override fun extractCrlUrl(certDer: ByteArray): String? = crlUrl

        override fun getCrlNextUpdateEpochSeconds(crlDer: ByteArray): Long? = crlNextUpdate

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
