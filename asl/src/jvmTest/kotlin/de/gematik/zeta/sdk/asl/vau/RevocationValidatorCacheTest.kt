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

import de.gematik.zeta.sdk.crypto.CrlValidity
import de.gematik.zeta.sdk.crypto.OcspRequestData
import de.gematik.zeta.sdk.crypto.OcspValidity
import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.network.http.client.CachedOcspResponse
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

class RevocationValidatorCacheTest {
    private val certDer = byteArrayOf(1, 2, 3)
    private val issuerDer = byteArrayOf(4, 5, 6)
    private val cachedResponseDer = byteArrayOf(9, 9, 9)
    private val now = 1_700_000_000L
    private val clock = ZetaClock {
        Instant.fromEpochSeconds(now)
    }

    /*
     * Direct OCSP responses go through fetchOcspDirect(), which rejects
     * unrealistically small responses before calling the handler.
     */
    private val fetchedOcspResponseDer = ByteArray(128) { index ->
        index.toByte()
    }

    @Test
    fun validate_usesCachedResponse_whenCacheHitAndNoStaple() = runTest {
        val storage = mockk<RevocationStorage>()

        coEvery {
            storage.getOcsp(any())
        } returns CachedOcspResponse(
            responseDer = cachedResponseDer,
            validatedAtEpochSeconds = Clock.System.now().epochSeconds,
            nextUpdateEpochSeconds = Clock.System.now().epochSeconds + 3600,
            thisUpdateEpochSeconds = Clock.System.now().epochSeconds,
        )

        val handler = mockk<RevocationHandler>(relaxed = true)

        val checker = RevocationChecker(
            storage = storage,
            httpClient = mockk(relaxed = true),
            handler = handler,
            clock = clock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        verify(exactly = 0) {
            handler.validate(any(), any(), any(), any())
        }

        coVerify(exactly = 0) {
            handler.prepareOcspRequest(any(), any())
        }

        coVerify(exactly = 0) {
            storage.setOcsp(any(), any())
        }
    }

    @Test
    fun validate_ignoresCache_whenStapleIsPresent() = runTest {
        val stapledResponse = byteArrayOf(7, 7, 7)
        val storage = mockk<RevocationStorage>(relaxed = true)
        val handler = mockk<RevocationHandler>(relaxed = true)

        every {
            handler.getOcspValidity(
                stapledResponse,
                certDer,
                issuerDer,
            )
        } returns OcspValidity(
            thisUpdateEpochSeconds = now,
            nextUpdateEpochSeconds = now + 3600,
        )

        val checker = RevocationChecker(
            storage = storage,
            httpClient = mockk(relaxed = true),
            handler = handler,
            clock = clock,
        )

        checker.validate(
            stapledOcspResponse = stapledResponse,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        verify(exactly = 1) {
            handler.validate(
                stapledResponse,
                certDer,
                issuerDer,
                Instant.fromEpochSeconds(now),
            )
        }

        coVerify(exactly = 0) {
            storage.getOcsp(any())
        }

        coVerify(exactly = 0) {
            handler.prepareOcspRequest(any(), any())
        }
    }

    @Test
    fun validate_fallsThroughToLiveCheck_whenCacheMiss() = runTest {
        val storage = mockk<RevocationStorage>(relaxed = true)

        coEvery {
            storage.getOcsp(any())
        } returns null

        val handler = mockk<RevocationHandler>(relaxed = true)

        coEvery {
            handler.prepareOcspRequest(certDer, issuerDer)
        } returns OcspRequestData(
            url = "http://ocsp.example.com",
            requestDer = byteArrayOf(1),
        )

        every {
            handler.getOcspValidity(
                fetchedOcspResponseDer,
                certDer,
                issuerDer,
            )
        } returns OcspValidity(
            thisUpdateEpochSeconds = now,
            nextUpdateEpochSeconds = now + 3600,
        )

        val checker = RevocationChecker(
            storage = storage,
            httpClient = mockHttpClient(
                responseBytes = fetchedOcspResponseDer,
            ),
            handler = handler,
            clock = clock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        coVerify(exactly = 1) {
            storage.getOcsp(any())
        }

        coVerify(exactly = 1) {
            handler.prepareOcspRequest(certDer, issuerDer)
        }

        verify(exactly = 1) {
            handler.validate(
                fetchedOcspResponseDer,
                certDer,
                issuerDer,
                Instant.fromEpochSeconds(now),
            )
        }

        coVerify(exactly = 1) {
            storage.setOcsp(
                any(),
                match {
                    it.responseDer.contentEquals(fetchedOcspResponseDer) &&
                        it.validatedAtEpochSeconds == now
                },
            )
        }
    }

    @Test
    fun validate_doesNotUseCrl_whenDirectOcspSucceeds() = runTest {
        val storage = mockk<RevocationStorage>(relaxed = true)

        coEvery {
            storage.getOcsp(any())
        } returns null

        val handler = mockk<RevocationHandler>(relaxed = true)

        coEvery {
            handler.prepareOcspRequest(certDer, issuerDer)
        } returns OcspRequestData(
            url = "http://ocsp.example.com",
            requestDer = byteArrayOf(1),
        )

        every {
            handler.getOcspValidity(
                fetchedOcspResponseDer,
                certDer,
                issuerDer,
            )
        } returns OcspValidity(
            thisUpdateEpochSeconds = Clock.System.now().epochSeconds,
            nextUpdateEpochSeconds = Clock.System.now().epochSeconds + 3600,
        )

        val checker = RevocationChecker(
            storage = storage,
            httpClient = mockHttpClient(
                responseBytes = fetchedOcspResponseDer,
            ),
            handler = handler,
            clock = clock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        verify(exactly = 0) {
            handler.extractCrlUrl(any())
        }

        verify(exactly = 0) {
            handler.validateCrl(any(), any(), any(), any())
        }

        coVerify(exactly = 0) {
            storage.getCrl(any())
        }
    }

    @Test
    fun tryDirectCrl_passesCrlBytes_notCertBytes_toGetCrlValidity() = runTest {
        val certDer = byteArrayOf(1, 2, 3)
        val issuerDer = byteArrayOf(4, 5, 6)
        val crlDer = byteArrayOf(9, 9, 9)

        val storage = mockk<RevocationStorage>(relaxed = true)
        coEvery { storage.getOcsp(any()) } returns null
        coEvery { storage.getCrl(any()) } returns null

        val handler = mockk<RevocationHandler>(relaxed = true)
        every { handler.extractCrlUrl(certDer) } returns "http://crl.example.com/crl.crl"
        every {
            handler.getCrlValidity(any())
        } returns CrlValidity(
            thisUpdateEpochSeconds = Clock.System.now().epochSeconds,
            nextUpdateEpochSeconds = Clock.System.now().epochSeconds + 3_600L,
        )

        val checker = RevocationChecker(
            storage = storage,
            httpClient = mockHttpClient(crlDer),
            handler = handler,
            clock = clock,
        )

        checker.validate(
            stapledOcspResponse = null,
            certDer = certDer,
            issuerDer = issuerDer,
        )

        verify(exactly = 1) {
            handler.getCrlValidity(
                match { it.contentEquals(crlDer) },
            )
        }

        verify(exactly = 0) {
            handler.getCrlValidity(
                match { it.contentEquals(certDer) },
            )
        }
    }

    private fun mockHttpClient(
        responseBytes: ByteArray = fetchedOcspResponseDer,
        throws: Boolean = false,
    ): ZetaHttpClient {
        val engine = MockEngine { request ->
            if (throws) {
                error("Fetch failed for ${request.url}")
            }
            respond(
                content = responseBytes,
                status = HttpStatusCode.OK,
            )
        }

        return ZetaHttpClient(HttpClient(engine))
    }
}
