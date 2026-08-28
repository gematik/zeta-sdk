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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock

@Serializable
public data class SerializableOcspResponse(
    val responseDerBase64: String,
    val expiresAtEpochSeconds: Long,
) {
    public fun toCached(): CachedOcspResponse = CachedOcspResponse(
        responseDer = Base64.decode(responseDerBase64),
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )

    public companion object {
        public fun from(c: CachedOcspResponse): SerializableOcspResponse = SerializableOcspResponse(
            responseDerBase64 = Base64.encode(c.responseDer),
            expiresAtEpochSeconds = c.expiresAtEpochSeconds,
        )
    }
}

public data class CachedOcspResponse(
    val responseDer: ByteArray,
    val expiresAtEpochSeconds: Long,
)

@Serializable
public data class SerializableCrlResponse(
    val crlDerBase64: String,
    val expiresAtEpochSeconds: Long,
) {
    public fun toCached(): CachedCrlResponse = CachedCrlResponse(
        crlDer = Base64.decode(crlDerBase64),
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )

    public companion object {
        public fun from(c: CachedCrlResponse): SerializableCrlResponse = SerializableCrlResponse(
            crlDerBase64 = Base64.encode(c.crlDer),
            expiresAtEpochSeconds = c.expiresAtEpochSeconds,
        )
    }
}

public data class CachedCrlResponse(
    val crlDer: ByteArray,
    val expiresAtEpochSeconds: Long,
)

public class RevocationStorage(
    storage: SdkStorage,
    private val resourceScope: ResourceScope,
    private val clock: Clock = Clock.System,
) {
    private val extendedStorage = ExtendedStorage(storage)
    private val mutex = Mutex()

    private val ocspIndexKey: String
        get() = "$OCSP_INDEX_PREFIX:${resourceScope.storageKey}"

    private val crlIndexKey: String
        get() = "$CRL_INDEX_PREFIX:${resourceScope.storageKey}"

    private fun scopedCacheKey(cacheKey: String): String =
        "${resourceScope.storageKey}:$cacheKey"

    public suspend fun getOcsp(
        cacheKey: String,
    ): CachedOcspResponse? = mutex.withLock {
        val entryKey = scopedCacheKey(cacheKey)

        val raw = extendedStorage.getIndexed(
            entryKey = entryKey,
            prefix = OCSP_PREFIX,
        ) ?: return@withLock null

        val cached = runCatching {
            Json.decodeFromString<SerializableOcspResponse>(raw)
                .toCached()
        }.getOrElse { exception ->
            Log.w {
                "[OCSP-CACHE] Failed to decode cached response: " +
                    "${exception.message}"
            }

            extendedStorage.removeIndexed(
                indexKey = ocspIndexKey,
                entryKey = entryKey,
                prefixes = listOf(OCSP_PREFIX),
            )
            return@withLock null
        }

        if (
            cached.expiresAtEpochSeconds <=
            clock.now().epochSeconds
        ) {
            Log.d {
                "[OCSP-CACHE] Cached response expired; removing it"
            }

            extendedStorage.removeIndexed(
                indexKey = ocspIndexKey,
                entryKey = entryKey,
                prefixes = listOf(OCSP_PREFIX),
            )

            return@withLock null
        }

        cached
    }

    public suspend fun setOcsp(
        cacheKey: String,
        response: CachedOcspResponse,
    ): Unit = mutex.withLock {
        val entryKey = scopedCacheKey(cacheKey)
        val encoded = Json.encodeToString(
            SerializableOcspResponse.from(response),
        )

        Log.d {
            "[OCSP-CACHE] storing storageKey=$entryKey " +
                "expiresAt=${response.expiresAtEpochSeconds}"
        }

        extendedStorage.putIndexed(
            indexKey = ocspIndexKey,
            entryKey = entryKey,
            entries = mapOf(
                OCSP_PREFIX to encoded,
            ),
        )
    }

    public suspend fun clearOcsp(
        cacheKey: String,
    ): Unit = mutex.withLock {
        extendedStorage.removeIndexed(
            indexKey = ocspIndexKey,
            entryKey = scopedCacheKey(cacheKey),
            prefixes = listOf(OCSP_PREFIX),
        )
    }

    public suspend fun getCrl(
        cacheKey: String,
    ): CachedCrlResponse? = mutex.withLock {
        val entryKey = scopedCacheKey(cacheKey)

        val raw = extendedStorage.getIndexed(
            entryKey = entryKey,
            prefix = CRL_PREFIX,
        ) ?: return@withLock null

        val cached = runCatching {
            Json.decodeFromString<SerializableCrlResponse>(raw)
                .toCached()
        }.getOrElse { exception ->
            Log.w {
                "[CRL-CACHE] Failed to decode cached response: " +
                    "${exception.message}"
            }

            extendedStorage.removeIndexed(
                indexKey = crlIndexKey,
                entryKey = entryKey,
                prefixes = listOf(CRL_PREFIX),
            )

            return@withLock null
        }

        if (
            cached.expiresAtEpochSeconds <=
            clock.now().epochSeconds
        ) {
            Log.d {
                "[CRL-CACHE] Cached response expired; removing it"
            }

            extendedStorage.removeIndexed(
                indexKey = crlIndexKey,
                entryKey = entryKey,
                prefixes = listOf(CRL_PREFIX),
            )

            return@withLock null
        }

        cached
    }

    public suspend fun setCrl(
        cacheKey: String,
        response: CachedCrlResponse,
    ): Unit = mutex.withLock {
        val entryKey = scopedCacheKey(cacheKey)
        val encoded = Json.encodeToString(
            SerializableCrlResponse.from(response),
        )

        Log.d {
            "[CRL-CACHE] storing storageKey=$entryKey " +
                "expiresAt=${response.expiresAtEpochSeconds}"
        }

        extendedStorage.putIndexed(
            indexKey = crlIndexKey,
            entryKey = entryKey,
            entries = mapOf(
                CRL_PREFIX to encoded,
            ),
        )
    }

    public suspend fun clearCrl(
        cacheKey: String,
    ): Unit = mutex.withLock {
        extendedStorage.removeIndexed(
            indexKey = crlIndexKey,
            entryKey = scopedCacheKey(cacheKey),
            prefixes = listOf(CRL_PREFIX),
        )
    }

    public suspend fun clear(): Unit = mutex.withLock {
        extendedStorage.clearAllIndexed(indexKey = ocspIndexKey, prefixes = listOf(OCSP_PREFIX))
        extendedStorage.clearAllIndexed(indexKey = crlIndexKey, prefixes = listOf(CRL_PREFIX))
        Log.d { "[REVOCATION-CACHE] cleared entries for ${resourceScope.storageKey}" }
    }

    private companion object {
        const val OCSP_PREFIX = "ocsp"
        const val CRL_PREFIX = "crl"

        const val OCSP_INDEX_PREFIX = "ocsp-index"
        const val CRL_INDEX_PREFIX = "crl-index"
    }
}
