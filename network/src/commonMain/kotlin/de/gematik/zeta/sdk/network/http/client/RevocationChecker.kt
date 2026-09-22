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
import de.gematik.zeta.sdk.crypto.CertificateRevokedException
import de.gematik.zeta.sdk.crypto.RevocationHandler
import de.gematik.zeta.sdk.crypto.RevocationHandlerImpl
import de.gematik.zeta.time.ZetaClock
import io.ktor.util.sha1
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

public class RevocationChecker(
    private val storage: RevocationStorage,
    private val httpClient: ZetaHttpClient,
    private val handler: RevocationHandler = RevocationHandlerImpl(),
    private val cacheDurationSeconds: Long = DEFAULT_REVOCATION_CACHE_SECONDS,
    private val allowSkipForTestCertificates: Boolean = false,
    private val clock: ZetaClock,
) {
    /**
     * Asks each source in turn - staple, OCSP (cache/network), CRL (cache/network) - and the
     * first to answer settles it; the check fails only if none can. Network answers are
     * cached under sha1(cert + issuer) once fully validated, so a hit needs only an expiry check;
     * an expired entry is dropped and the source asked again. A revocation throws
     * [CertificateRevokedException] and stops there - no later source may contradict it - while
     * any other failure just moves on to the next source.
     */
    public suspend fun validate(
        stapledOcspResponse: ByteArray?,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ) {
        try {
            validateAgainstSources(stapledOcspResponse, certDer, issuerDer)
        } catch (exception: CertificateRevokedException) {
            if (skipForTestCertificates("certificate is revoked (${exception.message})")) {
                return
            }
            throw exception
        }
    }

    private suspend fun validateAgainstSources(
        stapledOcspResponse: ByteArray?,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ) {
        Log.d {
            "[REVOCATION] start: staple=" +
                (stapledOcspResponse?.let { "${it.size} bytes" } ?: "none")
        }

        if (stapledOcspResponse != null) {
            tryStaple(stapledOcspResponse, certDer, issuerDer)
            return
        }

        Log.d { "[REVOCATION] no usable staple; trying OCSP" }

        val cacheKey = cacheKeyFor(certDer, issuerDer)

        val ocspAttempt = tryOcsp(cacheKey, certDer, issuerDer)
        if (ocspAttempt.success) {
            return
        }

        Log.d { "[REVOCATION] OCSP did not settle it; trying CRL" }

        val crlAttempt = tryCrl(cacheKey, certDer, issuerDer)
        if (crlAttempt.success) {
            return
        }

        if (skipForTestCertificates("every source failed")) {
            return
        }

        Log.e { "[REVOCATION] every source failed" }

        error(
            "Certificate revocation check failed: " +
                "no usable OCSP stapling or cache; direct OCSP failed (${ocspAttempt.error}); " +
                "CRL check failed (${crlAttempt.error})",
        )
    }

    private fun skipForTestCertificates(reason: String): Boolean {
        if (!allowSkipForTestCertificates) {
            return false
        }

        Log.w { "[REVOCATION] $reason; skipping because allowSkipForTestCertificates is enabled" }
        return true
    }

    /** OCSP from the cache, else straight from the responder. */
    private suspend fun tryOcsp(
        cacheKey: String,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): ValidationAttempt {
        if (tryCachedOcsp(cacheKey)) {
            return ValidationAttempt(success = true)
        }

        return tryDirectOcsp(cacheKey, certDer, issuerDer)
    }

    /** CRL from the cache, else straight from the distribution point. */
    private suspend fun tryCrl(
        cacheKey: String,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): ValidationAttempt {
        if (tryCachedCrl(cacheKey)) {
            return ValidationAttempt(success = true)
        }

        return tryDirectCrl(cacheKey, certDer, issuerDer)
    }

    /**
     * Validates a stapled OCSP response.
     *
     * Returns true when the staple settled the revocation check. Anything else about it -
     * stale, unreadable, forged, about another certificate - returns false, so the caller
     * falls back to the cache / direct OCSP / CRL chain and asks a source it can trust.
     *
     * The exception is a revocation: that is a signed answer, and it is thrown rather than
     * retried against a source that might be stale enough to contradict it.
     */
    private fun tryStaple(
        staple: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ) {
        Log.d { "[REVOCATION] staple: checking (${staple.size} bytes)" }

        val now = clock.now()
        val validity = handler.getOcspValidity(staple, certDer, issuerDer)

        check(
            isResponseFresh(
                nextUpdate = validity.nextUpdateEpochSeconds,
                thisUpdate = validity.thisUpdateEpochSeconds,
                nowSeconds = now.epochSeconds,
            ),
        ) {
            "Stapled OCSP response is stale: " +
                "thisUpdate=${validity.thisUpdateEpochSeconds}," +
                "nextUpdate=${validity.nextUpdateEpochSeconds}, now=${now.epochSeconds}"
        }

        handler.validate(staple, certDer, issuerDer, now)

        Log.d { "[REVOCATION] staple: accepted" }
    }

    private suspend fun tryCachedOcsp(cacheKey: String): Boolean {
        val cached = storage.getOcsp(cacheKey)
        if (cached == null) {
            Log.d { "[REVOCATION] cached OCSP: miss" }
            return false
        }

        val canReuse = canReuseCachedEntry(
            nextUpdate = cached.nextUpdateEpochSeconds,
            thisUpdate = cached.thisUpdateEpochSeconds,
            validatedAt = cached.validatedAtEpochSeconds,
            nowSeconds = clock.now().epochSeconds,
        )

        if (!canReuse) {
            Log.d {
                "[REVOCATION] cached OCSP: no longer reusable, removing " +
                    "(validatedAt=${cached.validatedAtEpochSeconds}, " +
                    "nextUpdate=${cached.nextUpdateEpochSeconds}, " +
                    "thisUpdate=${cached.thisUpdateEpochSeconds}, " +
                    "cacheDuration=${cacheDurationSeconds}s)"
            }
            storage.clearOcsp(cacheKey)
            return false
        }

        Log.d {
            "[REVOCATION] cached OCSP: hit (validatedAt=${cached.validatedAtEpochSeconds}, " +
                "nextUpdate=${cached.nextUpdateEpochSeconds}, " +
                "thisUpdate=${cached.thisUpdateEpochSeconds}, " +
                "cacheDuration=${cacheDurationSeconds}s)"
        }
        return true
    }

    private suspend fun tryCachedCrl(cacheKey: String): Boolean {
        val cached = storage.getCrl(cacheKey)
        if (cached == null) {
            Log.d { "[REVOCATION] cached CRL: miss" }
            return false
        }

        val canReuse = canReuseCachedEntry(
            nextUpdate = cached.nextUpdateEpochSeconds,
            thisUpdate = cached.thisUpdateEpochSeconds,
            validatedAt = cached.validatedAtEpochSeconds,
            nowSeconds = clock.now().epochSeconds,
        )

        if (!canReuse) {
            Log.d {
                "[REVOCATION] cached CRL: no longer reusable, removing " +
                    "(validatedAt=${cached.validatedAtEpochSeconds}, " +
                    "nextUpdate=${cached.nextUpdateEpochSeconds}, " +
                    "thisUpdate=${cached.thisUpdateEpochSeconds}, " +
                    "cacheDuration=${cacheDurationSeconds}s)"
            }
            storage.clearCrl(cacheKey)
            return false
        }

        Log.d {
            "[REVOCATION] cached CRL: hit (validatedAt=${cached.validatedAtEpochSeconds}, " +
                "nextUpdate=${cached.nextUpdateEpochSeconds}, " +
                "thisUpdate=${cached.thisUpdateEpochSeconds}, " +
                "cacheDuration=${cacheDurationSeconds}s)"
        }
        return true
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

            Log.d { "[REVOCATION] chain link ${index + 1}/$linksToCheck" }
            validate(staple, certDer, issuerDer)
        }
    }

    private suspend fun tryDirectOcsp(
        cacheKey: String,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): ValidationAttempt {
        return try {
            Log.d { "[REVOCATION] direct OCSP: requesting" }
            val requestData = handler.prepareOcspRequest(certDer, issuerDer)
            val response = fetchOcspDirect(
                url = requestData.url,
                requestDer = requestData.requestDer,
                httpClient = httpClient,
            )

            val now = clock.now()
            val (thisUpdate, nextUpdate) = handler.getOcspValidity(response, certDer, issuerDer)

            require(isResponseFresh(nextUpdate, thisUpdate, now.epochSeconds)) {
                "OCSP response is not fresh: " +
                    "thisUpdate=$thisUpdate, nextUpdate=$nextUpdate, now=$now"
            }

            handler.validate(response, certDer, issuerDer, now)

            storage.setOcsp(
                cacheKey,
                CachedOcspResponse(
                    responseDer = response,
                    validatedAtEpochSeconds = now.epochSeconds,
                    nextUpdateEpochSeconds = nextUpdate,
                    thisUpdateEpochSeconds = thisUpdate,
                ),
            )

            Log.d {
                "[REVOCATION] direct OCSP: accepted and cached " +
                    "(validatedAt=$now, nextUpdate=$nextUpdate, thisUpdate=$thisUpdate)"
            }
            ValidationAttempt(success = true)
        } catch (revoked: CertificateRevokedException) {
            throw revoked
        } catch (e: Exception) {
            val error = e.message ?: "Unknown OCSP error"
            Log.w { "[REVOCATION] direct OCSP: failed ($error)" }
            ValidationAttempt(success = false, error = error)
        }
    }

    private suspend fun tryDirectCrl(
        cacheKey: String,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ): ValidationAttempt {
        return try {
            val crlUrl = handler.extractCrlUrl(certDer)
            if (crlUrl == null) {
                Log.w { "[REVOCATION] direct CRL: certificate names no distribution point" }
                return ValidationAttempt(success = false, error = "No CRL URL in certificate")
            }

            Log.d { "[REVOCATION] direct CRL: fetching from $crlUrl" }
            val crlDer = httpClient.get(crlUrl).bodyAsBytes()
            Log.d { "[REVOCATION] direct CRL: fetched ${crlDer.size} bytes" }

            val now = clock.now()
            val (thisUpdate, nextUpdate) = handler.getCrlValidity(crlDer)

            require(isResponseFresh(nextUpdate, thisUpdate, now.epochSeconds)) {
                "CRL is not fresh: " +
                    "thisUpdate=$thisUpdate, nextUpdate=$nextUpdate, now=$now"
            }

            handler.validateCrl(crlDer, certDer, issuerDer, now)

            storage.setCrl(
                cacheKey,
                CachedCrlResponse(
                    crlDer = crlDer,
                    validatedAtEpochSeconds = now.epochSeconds,
                    nextUpdateEpochSeconds = nextUpdate,
                    thisUpdateEpochSeconds = thisUpdate,
                ),
            )

            Log.d {
                "[REVOCATION] direct CRL: accepted and cached " +
                    "(validatedAt=$now, nextUpdate=$nextUpdate, thisUpdate=$thisUpdate)"
            }
            ValidationAttempt(success = true)
        } catch (revoked: CertificateRevokedException) {
            throw revoked
        } catch (e: Exception) {
            val error = e.message ?: "Unknown CRL error"
            Log.w { "[REVOCATION] direct CRL: failed ($error)" }
            ValidationAttempt(success = false, error = error)
        }
    }

    private fun isResponseFresh(
        nextUpdate: Long?,
        thisUpdate: Long,
        nowSeconds: Long,
    ): Boolean = if (nextUpdate != null) {
        nowSeconds < nextUpdate
    } else {
        nowSeconds < thisUpdate + MAX_CACHE_AGE_NO_NEXT_UPDATE_SECONDS
    }

    private fun canReuseCachedEntry(
        nextUpdate: Long?,
        thisUpdate: Long,
        validatedAt: Long,
        nowSeconds: Long,
    ): Boolean = if (nextUpdate != null) {
        nowSeconds < minOf(nextUpdate, validatedAt + cacheDurationSeconds)
    } else {
        nowSeconds < thisUpdate + MAX_CACHE_AGE_NO_NEXT_UPDATE_SECONDS
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

public const val DEFAULT_REVOCATION_CACHE_SECONDS: Long = 3_600L
public const val MAX_CACHE_AGE_NO_NEXT_UPDATE_SECONDS: Long = 24 * 3_600L
