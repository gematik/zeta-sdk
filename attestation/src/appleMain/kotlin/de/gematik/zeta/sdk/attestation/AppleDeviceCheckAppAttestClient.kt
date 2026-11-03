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

import de.gematik.zeta.logging.Log
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.DeviceCheck.DCAppAttestService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val DC_ERROR_FEATURE_UNSUPPORTED = 1L
private const val DC_ERROR_INVALID_KEY = 3L
private const val DC_ERROR_SERVER_UNAVAILABLE = 4L
private const val DC_ERROR_DOMAIN = "com.apple.devicecheck.error"

internal fun isAppAttestSupported(): Boolean = DCAppAttestService.sharedService.isSupported()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AppleDeviceCheckAppAttestClient(
    private val service: DCAppAttestService = DCAppAttestService.sharedService,
) : AppAttestBridge {

    override suspend fun generateKey(): String = suspendCancellableCoroutine { cont ->
        service.generateKeyWithCompletionHandler { keyId, error ->
            when {
                error != null -> {
                    Log.e { "App Attest generateKey failed: ${error.toDebugString()}" }
                    cont.resumeWithException(AppAttestKeyGenerationFailed(error.toThrowable()))
                }
                keyId != null -> cont.resume(keyId)
                else -> cont.resumeWithException(AppAttestKeyGenerationFailed(null))
            }
        }
    }

    override suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray = suspendCancellableCoroutine { cont ->
        service.attestKey(keyId, clientDataHash.toNSData()) { attestation, error ->
            when {
                error != null -> {
                    Log.e { "App Attest attestKey failed: ${error.toDebugString()}" }
                    cont.resumeWithException(error.toAppAttestException())
                }
                attestation != null -> cont.resume(attestation.toByteArray())
                else -> cont.resumeWithException(AppAttestAttestationFailed(null))
            }
        }
    }

    override suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray = suspendCancellableCoroutine { cont ->
        service.generateAssertion(keyId, clientDataHash.toNSData()) { assertion, error ->
            when {
                error != null -> {
                    Log.e { "App Attest generateAssertion failed: ${error.toDebugString()}" }
                    cont.resumeWithException(error.toAppAttestException())
                }
                assertion != null -> cont.resume(assertion.toByteArray())
                else -> cont.resumeWithException(AppAttestAttestationFailed(null))
            }
        }
    }
}

private fun NSError.toAppAttestException(): AppAttestException = when {
    domain == DC_ERROR_DOMAIN && code == DC_ERROR_SERVER_UNAVAILABLE -> AppAttestServerUnavailable(toThrowable())
    domain == DC_ERROR_DOMAIN && code == DC_ERROR_FEATURE_UNSUPPORTED -> AppAttestUnsupported(toThrowable())
    domain == DC_ERROR_DOMAIN && code == DC_ERROR_INVALID_KEY -> AppAttestInvalidKey(toThrowable())
    else -> AppAttestAttestationFailed(toThrowable())
}

private fun NSError.toThrowable(): Throwable = RuntimeException(toDebugString())
private fun NSError.toDebugString(): String = "DCError domain=$domain code=$code desc=$localizedDescription"

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    if (isEmpty()) return NSData()
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val src = bytes?.reinterpret<ByteVar>() ?: error("NSData reports length=$len but has no backing bytes")
    return ByteArray(len) { src[it] }
}
