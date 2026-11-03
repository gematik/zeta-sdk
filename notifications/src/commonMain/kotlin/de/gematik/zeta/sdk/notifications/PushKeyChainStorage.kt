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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.json.Json

/**
 * Holds the [PushKeyChain] derived for the pusher registered from this device. Separate from
 * [LocalPusherStorage] because it carries key material rather than identity, but the two always
 * change together: a fresh [NotificationClientImpl.registerPusher] replaces both, and deleting
 * the local pusher clears both.
 */
interface PushKeyChainStorage {
    suspend fun get(): PushKeyChain?
    suspend fun save(chain: PushKeyChain)
    suspend fun clear()
}

internal class InMemoryPushKeyChainStorage : PushKeyChainStorage {
    private var chain: PushKeyChain? = null

    override suspend fun get(): PushKeyChain? = chain

    override suspend fun save(chain: PushKeyChain) {
        this.chain = chain
    }

    override suspend fun clear() {
        chain = null
    }
}

/** Persists [PushKeyChain] through the SDK's platform storage; see [LocalPusherStorageImpl] for the pattern this follows. */
class PushKeyChainStorageImpl(
    storage: SdkStorage,
    resourceScope: ResourceScope,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : PushKeyChainStorage {
    private val extended = ExtendedStorage(storage, resourceScope)

    override suspend fun get(): PushKeyChain? {
        val raw = extended.get(PUSH_KEY_CHAIN_KEY) ?: return null
        return runCatching { json.decodeFromString<PushKeyChain>(raw) }
            .onFailure { Log.e { "Corrupt push key chain entry in storage: ${it.message}" } }
            .getOrNull()
    }

    override suspend fun save(chain: PushKeyChain) {
        extended.put(PUSH_KEY_CHAIN_KEY, json.encodeToString(chain))
    }

    override suspend fun clear() {
        extended.remove(PUSH_KEY_CHAIN_KEY)
    }

    private companion object {
        const val PUSH_KEY_CHAIN_KEY = "push_key_chain"
    }
}
