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
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public interface AslStorage {
    public suspend fun saveSession(session: EstablishedSession)
    public suspend fun getCurrentSession(): EstablishedSession?

    public suspend fun getCachedCertData(
        certificateHashHex: String,
        certificateDescriptionVersion: Int,
    ): CertData?

    public suspend fun saveCachedCertData(
        certificateHashHex: String,
        certificateDescriptionVersion: Int,
        certData: CertData,
    )

    public suspend fun clear()
}

public class AslStorageImpl(
    storage: SdkStorage,
    resourceScope: ResourceScope,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : AslStorage {
    public companion object {
        public const val SESSION_PREFIX: String = "asl_session_by_resource"
        public const val INDEX_KEY: String = "asl_session_index"
        public const val ENTRY_KEY: String = "asl_session"
        public const val CERT_CACHE_INDEX_KEY: String = "asl_cert_cache_index"
        public const val CERT_CACHE_PREFIX: String = "asl_cert_cache"
    }

    private val extended = ExtendedStorage(storage, resourceScope)

    override suspend fun saveSession(session: EstablishedSession) {
        Log.d { "Saving ASL session" }
        extended.putIndexed(INDEX_KEY, ENTRY_KEY, mapOf(SESSION_PREFIX to json.encodeToString(session)))
    }

    override suspend fun getCurrentSession(): EstablishedSession? {
        Log.d { "Getting ASL session" }
        val value = extended.getIndexed(ENTRY_KEY, SESSION_PREFIX) ?: return null
        return runCatching { json.decodeFromString<EstablishedSession>(value) }.getOrElse {
            Log.e { "Error getting ASL session: ${it.message}" }
            throw it
        }
    }

    override suspend fun getCachedCertData(
        certificateHashHex: String,
        certificateDescriptionVersion: Int,
    ): CertData? {
        val key = certCacheEntryKey(certificateHashHex, certificateDescriptionVersion)

        Log.d { "Getting cached ASL certificate data: $key" }

        val value = extended.getIndexed(key, CERT_CACHE_PREFIX) ?: return null

        return runCatching {
            val cached = json.decodeFromString<CachedCertData>(value)

            CertData(
                cert = cached.cert,
                ca = cached.ca,
                rcaChain = cached.rcaChain,
            )
        }.getOrElse {
            Log.e { "Error getting cached ASL certificate data: ${it.message}" }
            throw it
        }
    }

    override suspend fun saveCachedCertData(
        certificateHashHex: String,
        certificateDescriptionVersion: Int,
        certData: CertData,
    ) {
        val key = certCacheEntryKey(certificateHashHex, certificateDescriptionVersion)

        Log.d { "Saving cached ASL certificate data: $key" }

        val cached = CachedCertData(
            cert = certData.cert,
            ca = certData.ca,
            rcaChain = certData.rcaChain,
        )

        extended.putIndexed(
            CERT_CACHE_INDEX_KEY,
            key,
            mapOf(CERT_CACHE_PREFIX to json.encodeToString(cached)),
        )
    }

    override suspend fun clear() {
        extended.clearIndexed(INDEX_KEY, ENTRY_KEY, listOf(SESSION_PREFIX))
        extended.clearAllIndexed(CERT_CACHE_INDEX_KEY, listOf(CERT_CACHE_PREFIX))
    }

    private fun certCacheEntryKey(
        certificateHashHex: String,
        certificateDescriptionVersion: Int,
    ): String =
        "$CERT_CACHE_PREFIX:$certificateDescriptionVersion:$certificateHashHex"
}

@Serializable
private data class CachedCertData(
    val cert: ByteArray,
    val ca: ByteArray,
    val rcaChain: List<ByteArray>,
)
