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

package de.gematik.zeta.sdk.crypto

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.crypto.openssl.ERR_clear_error
import de.gematik.zeta.sdk.crypto.openssl.ERR_error_string
import de.gematik.zeta.sdk.crypto.openssl.ERR_get_error
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestFinal_ex
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestInit_ex
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestSignFinal
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestSignInit
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestSignUpdate
import de.gematik.zeta.sdk.crypto.openssl.EVP_DigestUpdate
import de.gematik.zeta.sdk.crypto.openssl.EVP_MD_CTX_free
import de.gematik.zeta.sdk.crypto.openssl.EVP_MD_CTX_new
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_CTX_free
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_CTX_new
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_CTX_set_signature_md
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_EC
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_free
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_verify
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_verify_init
import de.gematik.zeta.sdk.crypto.openssl.EVP_sha256
import de.gematik.zeta.sdk.crypto.openssl.SHA256_DIGEST_LENGTH
import de.gematik.zeta.sdk.crypto.openssl.d2i_PUBKEY
import de.gematik.zeta.sdk.crypto.openssl.d2i_PrivateKey
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual class EcdhSigner {
    actual fun sign(privateKey: ByteArray, signingInput: ByteArray): ByteArray = memScoped {
        val pKey = alloc<CPointerVar<UByteVar>>()
        pKey.value = privateKey.refTo(0).getPointer(this).reinterpret()
        val evpKey = d2i_PrivateKey(EVP_PKEY_EC, null, pKey.ptr, privateKey.size.convert())
            ?: error("Failed to parse EC private key")

        try {
            val ctx = EVP_MD_CTX_new()
                ?: error("Failed to create MD context")

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

                signature.readBytes(sigLen.value.toInt())
            } finally {
                EVP_MD_CTX_free(ctx)
            }
        } finally {
            EVP_PKEY_free(evpKey)
        }
    }

    actual fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = memScoped {
        Log.i { "Verifying ES256 signature (${signature.size} bytes)" }

        ERR_clear_error()

        val pKey = alloc<CPointerVar<UByteVar>>()
        pKey.value = publicKey.refTo(0).getPointer(this).reinterpret()
        val evpKey = d2i_PUBKEY(null, pKey.ptr, publicKey.size.convert())

        if (evpKey == null) {
            Log.e { "Failed to parse public key" }
            return false
        }

        try {
            val derSignature = if (signature.size == RAW_SIGNATURE_SIZE) {
                Log.i { "Converting raw signature to DER" }
                rawToDer(signature)
            } else {
                signature
            }

            val hash = computeHash(data)
            val result = verifySignature(evpKey, hash, derSignature)

            if (result) {
                Log.i { "Signature verification successful" }
            } else {
                Log.e { "Signature verification failed" }
                logOpenSslError()
            }

            result
        } finally {
            EVP_PKEY_free(evpKey)
        }
    }

    private fun MemScope.computeHash(data: ByteArray): ByteArray {
        val hash = ByteArray(SHA256_DIGEST_LENGTH)
        val ctx = EVP_MD_CTX_new() ?: error("Failed to create hash context")

        return try {
            EVP_DigestInit_ex(ctx, EVP_sha256(), null)
            EVP_DigestUpdate(ctx, data.refTo(0), data.size.toULong())

            val hashLen = alloc<UIntVar>()
            EVP_DigestFinal_ex(ctx, hash.refTo(0).getPointer(this).reinterpret(), hashLen.ptr)

            hash
        } finally {
            EVP_MD_CTX_free(ctx)
        }
    }

    private fun MemScope.verifySignature(
        evpKey: CPointer<EVP_PKEY>,
        hash: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val ctx = EVP_PKEY_CTX_new(evpKey, null)
            ?: error("Failed to create PKEY context")

        try {
            if (EVP_PKEY_verify_init(ctx) != 1) {
                Log.e { "Failed to initialize verification" }
                return false
            }

            val mdResult = EVP_PKEY_CTX_set_signature_md(ctx, EVP_sha256())
            if (mdResult <= 0) {
                Log.w { "Signature MD setup returned $mdResult" }
            }

            ERR_clear_error()

            val result = EVP_PKEY_verify(
                ctx,
                signature.refTo(0).getPointer(this).reinterpret(),
                signature.size.toULong(),
                hash.refTo(0).getPointer(this).reinterpret(),
                hash.size.toULong(),
            )

            return result == 1
        } finally {
            EVP_PKEY_CTX_free(ctx)
        }
    }

    @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
    private fun logOpenSslError() {
        val err = ERR_get_error()
        if (err.toULong() != 0uL) {
            val errStr = ERR_error_string(err, null)?.toKString()
            Log.e { "OpenSSL: $errStr" }
        }
    }

    private fun rawToDer(rawSignature: ByteArray): ByteArray {
        require(rawSignature.size == RAW_SIGNATURE_SIZE) {
            "Raw signature must be $RAW_SIGNATURE_SIZE bytes"
        }

        val r = rawSignature.sliceArray(0 until COORDINATE_SIZE)
        val s = rawSignature.sliceArray(COORDINATE_SIZE until RAW_SIGNATURE_SIZE)

        val rDer = encodeInteger(r)
        val sDer = encodeInteger(s)
        val content = rDer + sDer

        return byteArrayOf(ASN1_SEQUENCE, content.size.toByte()) + content
    }

    private fun encodeInteger(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) {
            start++
        }
        val trimmed = value.sliceArray(start until value.size)
        val needsPadding = trimmed[0].toInt() and 0x80 != 0
        val padded = if (needsPadding) byteArrayOf(0x00) + trimmed else trimmed

        return byteArrayOf(ASN1_INTEGER, padded.size.toByte()) + padded
    }

    companion object {
        private const val RAW_SIGNATURE_SIZE = 64
        private const val COORDINATE_SIZE = 32
        private const val ASN1_SEQUENCE: Byte = 0x30
        private const val ASN1_INTEGER: Byte = 0x02
    }
}
