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
@file:OptIn(ExperimentalForeignApi::class)

package de.gematik.zeta.sdk.crypto

import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_CTX_free
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_CTX_new
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_CTX_new_from_name
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_decapsulate
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_decapsulate_init
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_encapsulate
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_encapsulate_init
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_free
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_get_raw_private_key
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_get_raw_public_key
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_keygen
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_keygen_init
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_new_raw_private_key_ex
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_new_raw_public_key_ex
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.posix.size_tVar

actual class ML768Kem actual constructor() : Kem {

    private val algorithmName = "ML-KEM-768"

    actual override fun generateKeys(): KeyPair = memScoped {
        val ctx = EVP_PKEY_CTX_new_from_name(null, algorithmName, null)
            ?: error("EVP_PKEY_CTX_new_from_name failed")

        EVP_PKEY_keygen_init(ctx).check()

        val pkeyVar = alloc<CPointerVar<EVP_PKEY>>()
        EVP_PKEY_keygen(ctx, pkeyVar.ptr).check()

        val pkey = pkeyVar.value!!

        val pub = extractKey(pkey, private = false)
        val priv = extractKey(pkey, private = true)

        EVP_PKEY_free(pkey)
        EVP_PKEY_CTX_free(ctx)

        KeyPair(
            skpi = pub,
            sec1 = null,
            privateKey = priv,
        )
    }

    actual override fun encapsulate(peerPublicKey: ByteArray): KemEncapResult = memScoped {
        val pubKey = loadPublicKey(peerPublicKey)

        val ctx = EVP_PKEY_CTX_new(pubKey, null)
            ?: error("EVP_PKEY_CTX_new failed")

        EVP_PKEY_encapsulate_init(ctx, null).check()

        val ctLen = alloc<size_tVar>()
        val ssLen = alloc<size_tVar>()

        EVP_PKEY_encapsulate(ctx, null, ctLen.ptr, null, ssLen.ptr)

        val ct = UByteArray(ctLen.value.toInt())
        val ss = UByteArray(ssLen.value.toInt())

        EVP_PKEY_encapsulate(
            ctx,
            ct.refTo(0),
            ctLen.ptr,
            ss.refTo(0),
            ssLen.ptr,
        ).check()

        EVP_PKEY_CTX_free(ctx)
        EVP_PKEY_free(pubKey)

        KemEncapResult(
            ciphertext = ct.toByteArray(),
            sharedSecret = ss.toByteArray(),
        )
    }

    actual override fun decapsulate(
        privateKeyRaw: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = memScoped {
        val privKey = loadPrivateKey(privateKeyRaw)

        val ctx = EVP_PKEY_CTX_new(privKey, null)
            ?: error("EVP_PKEY_CTX_new failed")

        EVP_PKEY_decapsulate_init(ctx, null).check()

        val ssLen = alloc<size_tVar>()
        EVP_PKEY_decapsulate(
            ctx,
            null,
            ssLen.ptr,
            ciphertext.toUByteArray().refTo(0),
            ciphertext.size.toULong(),
        )

        val ss = UByteArray(ssLen.value.toInt())

        EVP_PKEY_decapsulate(
            ctx,
            ss.refTo(0),
            ssLen.ptr,
            ciphertext.toUByteArray().refTo(0),
            ciphertext.size.toULong(),
        ).check()

        EVP_PKEY_CTX_free(ctx)
        EVP_PKEY_free(privKey)

        ss.toByteArray()
    }

    private fun extractKey(pkey: CPointer<EVP_PKEY>, private: Boolean): ByteArray = memScoped {
        val len = alloc<size_tVar>()
        if (private) {
            EVP_PKEY_get_raw_private_key(pkey, null, len.ptr).check()
        } else {
            EVP_PKEY_get_raw_public_key(pkey, null, len.ptr).check()
        }

        val buf = UByteArray(len.value.toInt())

        if (private) {
            EVP_PKEY_get_raw_private_key(pkey, buf.refTo(0), len.ptr).check()
        } else {
            EVP_PKEY_get_raw_public_key(pkey, buf.refTo(0), len.ptr).check()
        }
        buf.toByteArray()
    }

    private fun loadPublicKey(data: ByteArray): CPointer<EVP_PKEY> = memScoped {
        EVP_PKEY_new_raw_public_key_ex(
            null,
            "ML-KEM-768",
            null,
            data.toUByteArray().refTo(0),
            data.size.toULong(),
        ) ?: error("Failed to load public key")
    }

    private fun loadPrivateKey(data: ByteArray): CPointer<EVP_PKEY> = memScoped {
        EVP_PKEY_new_raw_private_key_ex(
            null,
            "ML-KEM-768",
            null,
            data.toUByteArray().refTo(0),
            data.size.toULong(),
        ) ?: error("Failed to load private key")
    }
}

private fun Int.check() {
    if (this <= 0) error("OpenSSL call failed: ${getOpenSSLErrors()}")
}
