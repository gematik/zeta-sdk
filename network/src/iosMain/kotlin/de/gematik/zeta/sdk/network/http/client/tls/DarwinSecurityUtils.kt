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

package de.gematik.zeta.sdk.network.http.client.tls

import de.gematik.zeta.logging.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRef
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal object DarwinSecurityUtils {
    fun evaluateTrust(trust: SecTrustRef, hostname: String): Boolean =
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val trusted = SecTrustEvaluateWithError(trust, error.ptr)

            if (!trusted) {
                val errorCode = error.value?.let { CFErrorGetCode(it) }
                Log.e { "[TLS] Apple trust FAILED host=$hostname errorCode=$errorCode" }
            }

            trusted
        }

    fun extractCertificateChain(trust: SecTrustRef): List<ByteArray> {
        val certificateChain = SecTrustCopyCertificateChain(trust) ?: error("SecTrustCopyCertificateChain returned null")

        try {
            val count = CFArrayGetCount(certificateChain)

            if (count <= 0) {
                error("SecTrust certificate chain is empty")
            }

            return (0 until count).map { index ->
                val certificate = CFArrayGetValueAtIndex(certificateChain, index) ?: error("Certificate at index $index is null")
                certificateToDer(certificate.reinterpret())
            }
        } finally {
            CFRelease(certificateChain)
        }
    }

    fun certificateToDer(certificate: SecCertificateRef): ByteArray {
        val data: CFDataRef = SecCertificateCopyData(certificate) ?: error("SecCertificateCopyData failed")

        try {
            val length = CFDataGetLength(data)
            if (length <= 0) {
                error("Certificate contains no DER data")
            }

            val source = CFDataGetBytePtr(data) ?: error("CFDataGetBytePtr failed")
            val result = ByteArray(length.toInt())

            result.usePinned {
                memcpy(
                    it.addressOf(0),
                    source,
                    length.convert(),
                )
            }

            return result
        } finally {
            CFRelease(data)
        }
    }

    fun dispatchDataToByteArray(data: dispatch_data_t): ByteArray {
        val size = dispatch_data_get_size(data).toInt()

        if (size <= 0) {
            return ByteArray(0)
        }

        val result = ByteArray(size)
        result.usePinned { pinned ->
            dispatch_data_apply(data) { _, offset, buffer, bufferSize ->
                memcpy(
                    pinned.addressOf(
                        offset.toInt(),
                    ),
                    buffer,
                    bufferSize,
                )

                true
            }
        }

        return result
    }
}
