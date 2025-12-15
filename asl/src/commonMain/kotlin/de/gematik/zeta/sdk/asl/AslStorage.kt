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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.json.Json

public interface AslStorage {
    public suspend fun saveSession(session: EstablishedSession)
    public suspend fun getCurrentSession(): EstablishedSession?
    public suspend fun clear()
}

public class AslStorageImpl(
    private val sdkStorage: SdkStorage,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },

) : AslStorage {
    override suspend fun saveSession(session: EstablishedSession) {
        Log.d { "Saving ASL session" }
        val value = runCatching { json.encodeToString<EstablishedSession>(session) }
            .getOrElse { e ->
                Log.e { "Failed to parse EstablishedSession. Not saving. Reason: ${e.message}" }
                throw e
            }

        sdkStorage.put(CURRENT_ASL_SESSION, value)
    }

    override suspend fun getCurrentSession(): EstablishedSession? {
        Log.d { "Restoring ASL session" }
        val value = sdkStorage.get(CURRENT_ASL_SESSION)
        if (value.isNullOrEmpty()) return null

        return runCatching { json.decodeFromString<EstablishedSession>(value) }
            .getOrElse { e ->
                Log.e { "Failed to parse EstablishedSession. Not saving. Reason: ${e.message}" }
                throw e
            }
    }

    override suspend fun clear() {
        Log.d { "Removing ASL session" }
        sdkStorage.remove(CURRENT_ASL_SESSION)
    }

    public companion object Companion {
        public const val CURRENT_ASL_SESSION: String = "current_asl_session"
    }
}
