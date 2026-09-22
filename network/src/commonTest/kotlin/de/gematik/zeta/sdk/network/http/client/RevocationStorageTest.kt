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
package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class RevocationStorageTest {

    private val now = Clock.System.now().epochSeconds
    private val farFuture = now + 3600

    private fun ocspEntry(
        der: ByteArray,
        nextUpdate: Long? = farFuture,
        thisUpdate: Long = now,
        validatedAt: Long = now,
    ) = CachedOcspResponse(
        responseDer = der,
        validatedAtEpochSeconds = validatedAt,
        nextUpdateEpochSeconds = nextUpdate,
        thisUpdateEpochSeconds = thisUpdate,
    )

    private fun crlEntry(
        der: ByteArray,
        nextUpdate: Long? = farFuture,
        thisUpdate: Long = now,
        validatedAt: Long = now,
    ) = CachedCrlResponse(
        crlDer = der,
        validatedAtEpochSeconds = validatedAt,
        nextUpdateEpochSeconds = nextUpdate,
        thisUpdateEpochSeconds = thisUpdate,
    )

    private fun buildStorage(
        scope: ResourceScope = ResourceScope("resource-a", emptyList()),
        backing: InMemoryStorage = InMemoryStorage(),
    ): RevocationStorage = RevocationStorage(storage = backing, resourceScope = scope)

    @Test
    fun get_returnsNull_whenNothingStored() = runTest {
        val cache = buildStorage()
        assertNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun set_thenGet_returnsSameResponse() = runTest {
        val cache = buildStorage()
        val response = ocspEntry(byteArrayOf(1, 2, 3, 4, 5))

        cache.setOcsp("cert-123", response)
        val result = cache.getOcsp("cert-123")

        assertNotNull(result)
        assertContentEquals(response.responseDer, result.responseDer)
        assertEquals(response.validatedAtEpochSeconds, result.validatedAtEpochSeconds)
        assertEquals(response.nextUpdateEpochSeconds, result.nextUpdateEpochSeconds)
        assertEquals(response.thisUpdateEpochSeconds, result.thisUpdateEpochSeconds)
    }

    @Test
    fun set_overwritesPreviousValue_forSameKey() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", ocspEntry(byteArrayOf(1)))
        cache.setOcsp("cert-123", ocspEntry(byteArrayOf(9, 9)))

        val result = cache.getOcsp("cert-123")

        assertNotNull(result)
        assertContentEquals(byteArrayOf(9, 9), result.responseDer)
    }

    @Test
    fun get_distinguishesDifferentCacheKeys() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-A", ocspEntry(byteArrayOf(1)))
        cache.setOcsp("cert-B", ocspEntry(byteArrayOf(2)))

        assertContentEquals(byteArrayOf(1), cache.getOcsp("cert-A")?.responseDer)
        assertContentEquals(byteArrayOf(2), cache.getOcsp("cert-B")?.responseDer)
    }

    @Test
    fun clear_removesEntry() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", ocspEntry(byteArrayOf(1)))

        cache.clearOcsp("cert-123")

        assertNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun clear_onMissingKey_doesNotThrow() = runTest {
        val cache = buildStorage()
        cache.clearOcsp("never-existed")
    }

    @Test
    fun differentResourceScopes_doNotShareCacheEntries() = runTest {
        val backing = InMemoryStorage()
        val cacheA = buildStorage(scope = ResourceScope("resource-a", emptyList()), backing = backing)
        val cacheB = buildStorage(scope = ResourceScope("resource-b", emptyList()), backing = backing)

        cacheA.setOcsp("cert-123", ocspEntry(byteArrayOf(1)))

        assertNotNull(cacheA.getOcsp("cert-123"))
        assertNull(cacheB.getOcsp("cert-123"))
    }

    @Test
    fun sameResourceScope_sharesCacheEntries_acrossInstances() = runTest {
        val backing = InMemoryStorage()
        val scope = ResourceScope("resource-a", emptyList())
        val cacheA = buildStorage(scope = scope, backing = backing)
        val cacheB = buildStorage(scope = scope, backing = backing)

        cacheA.setOcsp("cert-123", ocspEntry(byteArrayOf(1, 2)))

        val result = cacheB.getOcsp("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(1, 2), result.responseDer)
    }

    @Test
    fun set_thenGet_handlesEmptyResponseDer() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", ocspEntry(byteArrayOf()))

        val result = cache.getOcsp("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(), result.responseDer)
    }

    @Test
    fun serializableOcspResponse_roundTripsViaBase64() {
        val original = ocspEntry(byteArrayOf(0, -1, 127, -128, 5))

        val serialized = SerializableOcspResponse.from(original)
        val restored = serialized.toCached()

        assertContentEquals(original.responseDer, restored.responseDer)
        assertEquals(original.validatedAtEpochSeconds, restored.validatedAtEpochSeconds)
        assertEquals(original.nextUpdateEpochSeconds, restored.nextUpdateEpochSeconds)
        assertEquals(original.thisUpdateEpochSeconds, restored.thisUpdateEpochSeconds)
    }

    @Test
    fun getCrl_returnsNull_whenNothingStored() = runTest {
        val cache = buildStorage()
        assertNull(cache.getCrl("cert-123"))
    }

    @Test
    fun setCrl_thenGetCrl_returnsSameResponse() = runTest {
        val cache = buildStorage()
        val response = crlEntry(byteArrayOf(1, 2, 3, 4, 5))

        cache.setCrl("cert-123", response)
        val result = cache.getCrl("cert-123")

        assertNotNull(result)
        assertContentEquals(response.crlDer, result.crlDer)
        assertEquals(response.validatedAtEpochSeconds, result.validatedAtEpochSeconds)
        assertEquals(response.nextUpdateEpochSeconds, result.nextUpdateEpochSeconds)
        assertEquals(response.thisUpdateEpochSeconds, result.thisUpdateEpochSeconds)
    }

    @Test
    fun setCrl_overwritesPreviousValue_forSameKey() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", crlEntry(byteArrayOf(1)))
        cache.setCrl("cert-123", crlEntry(byteArrayOf(9, 9)))

        val result = cache.getCrl("cert-123")

        assertNotNull(result)
        assertContentEquals(byteArrayOf(9, 9), result.crlDer)
    }

    @Test
    fun getCrl_distinguishesDifferentCacheKeys() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-A", crlEntry(byteArrayOf(1)))
        cache.setCrl("cert-B", crlEntry(byteArrayOf(2)))

        assertContentEquals(byteArrayOf(1), cache.getCrl("cert-A")?.crlDer)
        assertContentEquals(byteArrayOf(2), cache.getCrl("cert-B")?.crlDer)
    }

    @Test
    fun clearCrl_removesEntry() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", crlEntry(byteArrayOf(1)))

        cache.clearCrl("cert-123")

        assertNull(cache.getCrl("cert-123"))
    }

    @Test
    fun clearCrl_onMissingKey_doesNotThrow() = runTest {
        val cache = buildStorage()
        cache.clearCrl("never-existed")
    }

    @Test
    fun differentResourceScopes_doNotShareCrlCacheEntries() = runTest {
        val backing = InMemoryStorage()
        val cacheA = buildStorage(scope = ResourceScope("resource-a", emptyList()), backing = backing)
        val cacheB = buildStorage(scope = ResourceScope("resource-b", emptyList()), backing = backing)

        cacheA.setCrl("cert-123", crlEntry(byteArrayOf(1)))

        assertNotNull(cacheA.getCrl("cert-123"))
        assertNull(cacheB.getCrl("cert-123"))
    }

    @Test
    fun sameResourceScope_sharesCrlCacheEntries_acrossInstances() = runTest {
        val backing = InMemoryStorage()
        val scope = ResourceScope("resource-a", emptyList())
        val cacheA = buildStorage(scope = scope, backing = backing)
        val cacheB = buildStorage(scope = scope, backing = backing)

        cacheA.setCrl("cert-123", crlEntry(byteArrayOf(1, 2)))

        val result = cacheB.getCrl("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(1, 2), result.crlDer)
    }

    @Test
    fun setCrl_thenGetCrl_handlesEmptyResponseDer() = runTest {
        val cache = buildStorage()
        cache.setCrl("cert-123", crlEntry(byteArrayOf()))

        val result = cache.getCrl("cert-123")
        assertNotNull(result)
        assertContentEquals(byteArrayOf(), result.crlDer)
    }

    @Test
    fun serializableCrlResponse_roundTripsViaBase64() {
        val original = crlEntry(byteArrayOf(0, -1, 127, -128, 5))

        val serialized = SerializableCrlResponse.from(original)
        val restored = serialized.toCached()

        assertContentEquals(original.crlDer, restored.crlDer)
        assertEquals(original.validatedAtEpochSeconds, restored.validatedAtEpochSeconds)
        assertEquals(original.nextUpdateEpochSeconds, restored.nextUpdateEpochSeconds)
        assertEquals(original.thisUpdateEpochSeconds, restored.thisUpdateEpochSeconds)
    }

    @Test
    fun clear_removesBothOcspAndCrlEntries() = runTest {
        val cache = buildStorage()
        cache.setOcsp("cert-123", ocspEntry(byteArrayOf(1)))
        cache.setCrl("cert-123", crlEntry(byteArrayOf(2)))

        cache.clear()

        assertNull(cache.getOcsp("cert-123"))
        assertNull(cache.getCrl("cert-123"))
    }

    @Test
    fun getOcsp_dropsEntryWrittenInTheOldFormat() = runTest {
        val scope = ResourceScope("resource-a", emptyList())
        val backing = InMemoryStorage()
        val cache = buildStorage(scope = scope, backing = backing)

        // what a pre-migration SDK version wrote: expiresAt, no next/thisUpdate
        backing.put(
            "ocsp:" + ExtendedStorage.hash("${scope.storageKey}:cert-123"),
            """{"responseDerBase64":"AQ==","expiresAtEpochSeconds":$farFuture,"validatedAtEpochSeconds":$now}""",
        )

        assertNull(cache.getOcsp("cert-123"))
    }

    @Test
    fun getCrl_dropsEntryWrittenInTheOldFormat() = runTest {
        val scope = ResourceScope("resource-a", emptyList())
        val backing = InMemoryStorage()
        val cache = buildStorage(scope = scope, backing = backing)

        backing.put(
            "crl:" + ExtendedStorage.hash("${scope.storageKey}:cert-123"),
            """{"crlDerBase64":"AQ==","expiresAtEpochSeconds":$farFuture,"validatedAtEpochSeconds":$now}""",
        )

        assertNull(cache.getCrl("cert-123"))
    }
}
