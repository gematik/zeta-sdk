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

import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val TEST_RESOURCE_SCOPE = ResourceScope("https://guard.example", listOf("scope"))

class PushKeyChainStorageImplTest {

    private fun chain() = PushKeyChain(listOf(derivePushKeyMaterial(ByteArray(32) { it.toByte() }, "2026-08")))

    /** [PushKeyChain]/[PushKeyMaterial]'s generated `equals` compares `ByteArray` fields by reference, not content. */
    private fun assertChainContentEquals(expected: PushKeyChain, actual: PushKeyChain?) {
        val actualEntries = actual?.entries
        assertEquals(expected.entries.map { it.yearMonth }, actualEntries?.map { it.yearMonth })
        expected.entries.zip(actualEntries.orEmpty()).forEach { (e, a) ->
            assertContentEquals(e.sharedSecret, a.sharedSecret)
            assertContentEquals(e.aesKey, a.aesKey)
        }
    }

    @Test
    fun `get returns null before anything is saved`() = runTest {
        val storage = PushKeyChainStorageImpl(InMemoryStorage(), TEST_RESOURCE_SCOPE)

        assertNull(storage.get())
    }

    @Test
    fun `round-trips a saved chain, including its binary key material`() = runTest {
        val storage = PushKeyChainStorageImpl(InMemoryStorage(), TEST_RESOURCE_SCOPE)
        val chain = chain()

        storage.save(chain)

        assertChainContentEquals(chain, storage.get())
    }

    @Test
    fun `survives a new storage instance over the same backing SdkStorage`() = runTest {
        val backing = InMemoryStorage()
        val chain = chain()
        PushKeyChainStorageImpl(backing, TEST_RESOURCE_SCOPE).save(chain)

        val reloaded = PushKeyChainStorageImpl(backing, TEST_RESOURCE_SCOPE).get()

        assertChainContentEquals(chain, reloaded)
    }

    @Test
    fun `clear removes the saved chain`() = runTest {
        val storage = PushKeyChainStorageImpl(InMemoryStorage(), TEST_RESOURCE_SCOPE)
        storage.save(chain())

        storage.clear()

        assertNull(storage.get())
    }

    @Test
    fun `a corrupted entry is treated as absent rather than thrown`() = runTest {
        val backing = InMemoryStorage()
        val storage = PushKeyChainStorageImpl(backing, TEST_RESOURCE_SCOPE)
        storage.save(chain())
        val key = backing.map.keys.single()
        backing.put(key, "not valid json")

        assertNull(storage.get())
    }
}
