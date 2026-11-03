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
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val TEST_RESOURCE_SCOPE = ResourceScope("https://guard.example", listOf("scope"))

class LocalPusherStorageImplTest {

    @Test
    fun `get returns null before anything is saved`() = runTest {
        val storage = LocalPusherStorageImpl(InMemoryStorage(), TEST_RESOURCE_SCOPE)

        assertNull(storage.get())
    }

    @Test
    fun `round-trips a saved pusher`() = runTest {
        val storage = LocalPusherStorageImpl(InMemoryStorage(), TEST_RESOURCE_SCOPE)
        val pusher = LocalPusher(pushkey = "token", appId = "de.example.app")

        storage.save(pusher)

        assertEquals(pusher, storage.get())
    }

    @Test
    fun `survives a new storage instance over the same backing SdkStorage`() = runTest {
        val backing = InMemoryStorage()
        val pusher = LocalPusher(pushkey = "token", appId = "de.example.app")
        LocalPusherStorageImpl(backing, TEST_RESOURCE_SCOPE).save(pusher)

        val reloaded = LocalPusherStorageImpl(backing, TEST_RESOURCE_SCOPE).get()

        assertEquals(pusher, reloaded)
    }

    @Test
    fun `clear removes the saved pusher`() = runTest {
        val storage = LocalPusherStorageImpl(InMemoryStorage(), TEST_RESOURCE_SCOPE)
        storage.save(LocalPusher(pushkey = "token", appId = "de.example.app"))

        storage.clear()

        assertNull(storage.get())
    }

    @Test
    fun `a corrupted entry is treated as absent rather than thrown`() = runTest {
        val backing = InMemoryStorage()
        val storage = LocalPusherStorageImpl(backing, TEST_RESOURCE_SCOPE)
        storage.save(LocalPusher(pushkey = "token", appId = "de.example.app"))
        val key = backing.map.keys.single()
        backing.put(key, "not valid json")

        assertNull(storage.get())
    }
}
