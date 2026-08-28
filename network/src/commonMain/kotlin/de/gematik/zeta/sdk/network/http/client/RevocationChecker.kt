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
import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.crypto.RevocationHandlerImpl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.util.sha1
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

public class RevocationChecker(
    private val storage: RevocationStorage,
    private val httpClient: HttpClient = HttpClient(),
    private val handler: RevocationHandler = RevocationHandlerImpl(),
    private val cacheDurationSeconds: Long = DEFAULT_MIN_REVOCATION_CACHE_SECONDS,
    private val allowSkipForTestCertificates: Boolean = false,
    private val clock: Clock = Clock.System,
) {
    public suspend fun validate(
        stapledOcspResponse: ByteArray?,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ) {
        if (stapledOcspResponse != null) {
            Log.i { "Using OCSP stapling response (${stapledOcspResponse.size} bytes)" }
            validateOcspResponse(stapledOcspResponse, certDer, issuerDer)
            return
        }

        val cacheKey = cacheKeyFor(certDer, issuerDer)

        val ocspAttempt = cacheOrAttempt(
            cached = storage.getOcsp(cacheKey),
            onCacheHit = { Log.i { "Using cached OCSP response (valid until ${it.expiresAtEpochSeconds})" } },
        ) {
            Log.w { "No OCSP stapling and no valid OCSP cache. Attempting direct OCSP" }
            tryDirectOcsp(certDer, issuerDer)
        }

        if (ocspAttempt.success) {
            Log.i { "Successfully validated via direct OCSP" }
            return
        }

        val crlAttempt = cacheOrAttempt(
            cached = storage.getCrl(cacheKey),
            onCacheHit = { Log.i { "Using cached CRL response (valid until ${it.expiresAtEpochSeconds})" } },
        ) {
            Log.w { "Direct OCSP failed: ${ocspAttempt.error}; attempting CRL" }
            tryDirectCrl(certDer, issuerDer)
        }

        if (crlAttempt.success) {
            Log.i { "Successfully validated via CRL" }
            return
        }

        Log.e { "CRL check failed: ${crlAttempt.error}" }

        if (allowSkipForTestCertificates) {
            Log.w { "Skipping revocation check because allowSkipForTestCertificates is enabled" }
            return
        }

        error(
            "Certificate revocation check failed: " +
                "no OCSP stapling; direct OCSP failed (${ocspAttempt.error}); " +
                "CRL check failed (${crlAttempt.error})",
        )
    }

    private suspend fun <T> cacheOrAttempt(
        cached: T?,
        onCacheHit: (T) -> Unit,
        direct: suspend () -> ValidationAttempt,
    ): ValidationAttempt {
        if (cached != null) {
            onCacheHit(cached)
            return ValidationAttempt(success = true)
        }
        return direct()
    }

    public suspend fun validateChain(
        stapledOcspResponse: ByteArray?,
        chain: List<ByteArray>,
    ) {
        require(chain.size >= 2) {
            "Certificate chain must contain at least leaf + issuer, got ${chain.size}"
        }

        // Exclude the root: it's self-signed
        val linksToCheck = chain.size - 1

        for (index in 0 until linksToCheck) {
            val certDer = chain[index]
            val issuerDer = chain[index + 1]
            val staple = if (index == 0) stapledOcspResponse else null

            Log.i { "Validating revocation for chain link ${index + 1}/$linksToCheck" }
            validate(staple, certDer, issuerDer)
        }
    }

    private suspend fun tryDirectOcsp(
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): ValidationAttempt {
        return try {
            val cacheKey = cacheKeyFor(certDer, issuerDer)

            val requestData = handler.prepareOcspRequest(certDer, issuerDer)
            val response = fetchOcspDirect(
                url = requestData.url,
                requestDer = requestData.requestDer,
                httpClient = httpClient,
            )
            val expiresAt = validateOcspResponse(response, certDer, issuerDer)

            storage.setOcsp(cacheKey, CachedOcspResponse(response, expiresAt))

            ValidationAttempt(success = true)
        } catch (e: Exception) {
            ValidationAttempt(
                success = false,
                error = e.message ?: "Unknown OCSP error",
            )
        }
    }

    private suspend fun tryDirectCrl(
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): ValidationAttempt {
        return try {
            val cacheKey = cacheKeyFor(certDer, issuerDer)

            val crlUrl = handler.extractCrlUrl(certDer)
                ?: return ValidationAttempt(success = false, error = "No CRL URL in certificate")

            Log.i { "Fetching CRL from: $crlUrl" }
            val crlDer = httpClient.get(crlUrl).bodyAsBytes()
            Log.i { "CRL fetched: ${crlDer.size} bytes" }

            handler.validateCrl(crlDer, certDer, issuerDer)

            val expiresAt = resolveExpiresAt(
                handler.getCrlNextUpdateEpochSeconds(crlDer),
            )

            storage.setCrl(cacheKey, CachedCrlResponse(crlDer, expiresAt))

            ValidationAttempt(success = true)
        } catch (e: Exception) {
            ValidationAttempt(success = false, error = e.message ?: "Unknown CRL error")
        }
    }

    public fun resolveExpiresAt(nextUpdate: Long?): Long {
        return if (nextUpdate != null) {
            Log.i { "Revocation cache: using nextUpdate=$nextUpdate" }
            nextUpdate
        } else {
            val fallback = clock.now().epochSeconds + cacheDurationSeconds
            Log.i { "Revocation cache: no nextUpdate, fallback to configuredTime=${cacheDurationSeconds}s (expires=$fallback)" }
            fallback
        }
    }

    private fun validateOcspResponse(
        ocspResponse: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): Long {
        val nowSeconds = clock.now().epochSeconds

        val nextUpdate = handler.getNextUpdateEpochSeconds(
            ocspResponse,
            certDer,
            issuerDer,
        )

        if (nextUpdate != null) {
            require(nowSeconds < nextUpdate) {
                "OCSP response expired: nextUpdate=$nextUpdate, now=$nowSeconds"
            }
        } else {
            val thisUpdate = handler.getThisUpdateEpochSeconds(ocspResponse)
            require(nowSeconds < thisUpdate + cacheDurationSeconds) {
                "OCSP response too old: thisUpdate=$thisUpdate, now=$nowSeconds"
            }
        }

        handler.validate(
            ocspResponse,
            certDer,
            issuerDer,
        )

        return resolveExpiresAt(nextUpdate)
    }

    public suspend fun clear() {
        storage.clear()
    }
}

public data class ValidationAttempt(
    val success: Boolean,
    val error: String? = null,
)

@OptIn(ExperimentalEncodingApi::class)
public fun cacheKeyFor(
    certDer: ByteArray,
    issuerDer: ByteArray,
): String {
    return Base64.encode(
        sha1(certDer + issuerDer),
    )
}

public const val DEFAULT_MIN_REVOCATION_CACHE_SECONDS: Long = 3_600L
