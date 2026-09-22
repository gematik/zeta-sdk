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

import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.time.SystemZetaClock
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AslTiRootStoreTest {
    private fun fakeClock(epochSeconds: Long): ZetaClock = ZetaClock { Instant.fromEpochSeconds(epochSeconds) }
    private fun rootsJson(certDer: ByteArray): String =
        """
    [
        {
            "cert":"${Base64.encode(certDer)}",
            "name":"test",
            "nvb":"2020-01-01",
            "nva":"2030-01-01"
        }
    ]
        """.trimIndent()
    private var fetchCount = 0

    private fun mockHttpClient(
        responseJson: String = emptyRootsJson,
        throws: Boolean = false,
    ): ZetaHttpClient {
        fetchCount = 0
        val engine = MockEngine { _ ->
            fetchCount++
            if (throws) error("Network error")
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return ZetaHttpClient(HttpClient(engine))
    }

    private val emptyRootsJson = "[]"

    val rootsJson: String = """[{"cert":"bm90YXZhbGlkY2VydA==","name":"test","nvb":"2020-01-01","nva":"2030-01-01"}]"""

    @Test
    fun tiEnvironment_production_hasCorrectUrl() {
        assertEquals(
            "https://download.tsl.ti-dienste.de/ECC/ROOT-CA/roots.json",
            AslTiRootStore.TiEnvironment.PRODUCTION.url,
        )
    }

    @Test
    fun tiEnvironment_reference_hasCorrectUrl() {
        assertEquals(
            "https://download-ref.tsl.ti-dienste.de/ECC/ROOT-CA/roots.json",
            AslTiRootStore.TiEnvironment.REFERENCE.url,
        )
    }

    @Test
    fun getTrustAnchors_fetchesFromNetwork_onFirstCall() = runTest {
        val store = AslTiRootStore(mockHttpClient())
        store.getTrustAnchors(fakeClock(1000L))
        assertEquals(1, fetchCount)
    }

    @Test
    fun getTrustAnchors_returnsEmptyList_whenJsonIsEmpty() = runTest {
        val store = AslTiRootStore(mockHttpClient(emptyRootsJson))
        val result = store.getTrustAnchors(fakeClock(1000L))
        assertTrue(result.isEmpty())
    }

    @Test
    fun getTrustAnchors_throwsException_onNetworkError() = runTest {
        val store = AslTiRootStore(mockHttpClient(throws = true))
        kotlin.test.assertFailsWith<Exception> {
            store.getTrustAnchors(fakeClock(1000L))
        }
    }

    @Test
    fun getTrustAnchors_refreshes_whenCacheIsStale() = runTest {
        val store = AslTiRootStore(mockHttpClient())
        val now = 1000L

        store.getTrustAnchors(fakeClock(now))
        store.getTrustAnchors(fakeClock(now + 25 * 3600))

        assertEquals(2, fetchCount)
    }

    @Test
    fun getTrustAnchors_refreshes_exactlyAtRefreshInterval() = runTest {
        val store = AslTiRootStore(mockHttpClient())
        val now = 1000L

        store.getTrustAnchors(fakeClock(now))
        store.getTrustAnchors(fakeClock(now + 24 * 3600 + 1))

        assertEquals(2, fetchCount)
    }

    @Test
    fun defaultEnvironment_isReference() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                emptyRootsJson, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val store = AslTiRootStore(ZetaHttpClient(HttpClient(engine)))
        store.getTrustAnchors(fakeClock(1000L))

        assertTrue(capturedUrl.contains("download-ref.tsl.ti-dienste.de"))
    }

    @Test
    fun productionEnvironment_usesProductionUrl() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                emptyRootsJson, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val store = AslTiRootStore(
            ZetaHttpClient(HttpClient(engine)),
            AslTiRootStore.TiEnvironment.PRODUCTION,
        )
        store.getTrustAnchors(fakeClock(1000L))

        assertTrue(capturedUrl.contains("download.tsl.ti-dienste.de"))
    }

    @Test
    fun getTrustAnchors_filtersOut_invalidCerts() = runTest {
        val store = AslTiRootStore(mockHttpClient(rootsJson))

        val result = store.getTrustAnchors(fakeClock(1000L))

        assertTrue(result.isEmpty())
    }

    @Test
    fun getTrustAnchors_ignoresUnknownJsonFields() = runTest {
        val json = """[{"cert":"","name":"x","nvb":"2020","nva":"2030","unknown_field":"value"}]"""
        val store = AslTiRootStore(mockHttpClient(json))

        store.getTrustAnchors(fakeClock(1000L))
    }

    @Test
    fun getTrustAnchors_usesSystemClock_byDefault() = runTest {
        val store = AslTiRootStore(mockHttpClient())

        store.getTrustAnchors(SystemZetaClock)

        assertEquals(1, fetchCount)
    }

    @Test
    fun getTrustAnchors_refreshes_afterRefreshInterval() = runTest {
        val store = AslTiRootStore(
            mockHttpClient(
                rootsJson("bm90YXZhbGlkY2VydA==".toByteArray()),
            ),
        )
        val now = 1000L

        store.getTrustAnchors(fakeClock(now))
        store.getTrustAnchors(fakeClock(now + 24 * 3600 + 1))

        assertEquals(2, fetchCount)
    }
}
