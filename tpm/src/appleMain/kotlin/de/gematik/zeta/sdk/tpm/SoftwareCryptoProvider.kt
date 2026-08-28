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

package de.gematik.zeta.sdk.tpm

import de.gematik.zeta.sdk.crypto.X509PemReader
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestSignFinal
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestSignInit
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestSignUpdate
import de.gematik.zeta.sdk.crypto.openssl.EVP_MD_CTX_free
import de.gematik.zeta.sdk.crypto.openssl.EVP_MD_CTX_new
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_free
import de.gematik.zeta.sdk.crypto.openssl.EVP_sha256
import de.gematik.zeta.sdk.crypto.openssl.d2i_AutoPrivateKey
import derEcdsaToJose
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value

internal class SoftwareCryptoProvider(
    storage: TpmStorage,
    x509PemReader: X509PemReader,
) : NativeSoftwareCryptoProvider(storage, x509PemReader) {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun signSmb(privateKey: ByteArray, signingInput: ByteArray): ByteArray = memScoped {
        val pKey = alloc<CPointerVar<UByteVar>>()
        pKey.value = privateKey.refTo(0).getPointer(this).reinterpret()
        val evpKey = d2i_AutoPrivateKey(null, pKey.ptr, privateKey.size.convert())
            ?: error("Failed to parse brainpool private key")

        try {
            val ctx = EVP_MD_CTX_new() ?: error("Failed to create MD context")
            try {
                require(EVP_DigestSignInit(ctx, null, EVP_sha256(), null, evpKey) == 1) {
                    "Failed to initialize signing"
                }
                require(EVP_DigestSignUpdate(ctx, signingInput.refTo(0), signingInput.size.toULong()) == 1) {
                    "Failed to update signing"
                }
                val sigLen = alloc<ULongVar>()
                EVP_DigestSignFinal(ctx, null, sigLen.ptr)
                val signature = allocArray<UByteVar>(sigLen.value.toInt())
                require(EVP_DigestSignFinal(ctx, signature, sigLen.ptr) == 1) {
                    "Failed to finalize signing"
                }
                derEcdsaToJose(signature.readBytes(sigLen.value.toInt()), 32)
            } finally {
                EVP_MD_CTX_free(ctx)
            }
        } finally {
            EVP_PKEY_free(evpKey)
        }
    }
}
