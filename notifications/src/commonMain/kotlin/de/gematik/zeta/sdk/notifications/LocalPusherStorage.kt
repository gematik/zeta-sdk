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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalPusher(
    val pushkey: String,
    val appId: String,
)

/**
 * Holds the pusher last registered from this SDK instance, which the pushkey-less
 * [NotificationClient] operations target.
 */
interface LocalPusherStorage {
    suspend fun get(): LocalPusher?
    suspend fun save(pusher: LocalPusher)
    suspend fun clear()
}

internal class InMemoryLocalPusherStorage : LocalPusherStorage {
    private var pusher: LocalPusher? = null

    override suspend fun get(): LocalPusher? = pusher

    override suspend fun save(pusher: LocalPusher) {
        this.pusher = pusher
    }

    override suspend fun clear() {
        pusher = null
    }
}

/** Persists [LocalPusher] through the SDK's platform storage, following the same [ExtendedStorage] pattern as [de.gematik.zeta.sdk.tpm.TpmStorageImpl]/[de.gematik.zeta.sdk.asl.AslStorageImpl]. */
class LocalPusherStorageImpl(
    storage: SdkStorage,
    resourceScope: ResourceScope,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : LocalPusherStorage {
    private val extended = ExtendedStorage(storage, resourceScope)

    override suspend fun get(): LocalPusher? {
        val raw = extended.get(LOCAL_PUSHER_KEY) ?: return null
        return runCatching { json.decodeFromString<LocalPusher>(raw) }
            .onFailure { Log.e { "Corrupt local pusher entry in storage: ${it.message}" } }
            .getOrNull()
    }

    override suspend fun save(pusher: LocalPusher) {
        extended.put(LOCAL_PUSHER_KEY, json.encodeToString(pusher))
    }

    override suspend fun clear() {
        extended.remove(LOCAL_PUSHER_KEY)
    }

    private companion object {
        const val LOCAL_PUSHER_KEY = "local_pusher"
    }
}
