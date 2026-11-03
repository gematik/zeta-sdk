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

package de.gematik.zeta.sdk.attestation

import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import de.gematik.zeta.android.baseContext
import de.gematik.zeta.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private val PLAY_INTEGRITY_TIMEOUT = 10.seconds
private val DIRECT_EXECUTOR = Executor(Runnable::run)

/** [PlayIntegrity] backed by the Google Play Integrity library; managers are created lazily on first use. */
@Suppress("TooGenericExceptionCaught")
internal class GooglePlayIntegrity(
    private val cloudProjectNumber: Long,
    integrityManager: () -> IntegrityManager = { IntegrityManagerFactory.create(baseContext) },
    standardIntegrityManager: () -> StandardIntegrityManager = { IntegrityManagerFactory.createStandard(baseContext) },
) : PlayIntegrity {

    private val classicManager by lazy(integrityManager)
    private val standardManager by lazy(standardIntegrityManager)
    private val providerMutex = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun requestIntegrityToken(nonce: String): String? = guarded("classic") {
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .setCloudProjectNumber(cloudProjectNumber)
            .build()
        classicManager.requestIntegrityToken(request).await().token()
    }

    override suspend fun requestStandardToken(requestHash: String): String? = guarded("standard") {
        providerMutex.withLock {
            val current = provider ?: standardManager
                .prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
                .await()
                .also { provider = it }
            val request = StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()
            try {
                current.request(request).await().token()
            } catch (e: StandardIntegrityException) {
                if (e.errorCode == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) provider = null
                throw e
            }
        }
    }

    private suspend fun guarded(kind: String, block: suspend () -> String): String? = try {
        withTimeoutOrNull(PLAY_INTEGRITY_TIMEOUT) { block() }
            ?: null.also { Log.w { "Play Integrity $kind request timed out; sending without play_integrity_token" } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val code = (e as? StandardIntegrityException)?.errorCode ?: (e as? IntegrityServiceException)?.errorCode
        Log.w { "Play Integrity $kind request failed (code $code: ${e.message}); sending without play_integrity_token" }
        null
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(DIRECT_EXECUTOR) { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Play Integrity task was cancelled"))
        }
    }
}
