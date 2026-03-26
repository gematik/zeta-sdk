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
import de.gematik.zeta.sdk.crypto.openssl.ASN1_OBJECT
import de.gematik.zeta.sdk.crypto.openssl.ASN1_TIME
import de.gematik.zeta.sdk.crypto.openssl.ASN1_TIME_compare
import de.gematik.zeta.sdk.crypto.openssl.ASN1_TIME_set
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_free
import de.gematik.zeta.sdk.crypto.openssl.NID_ext_key_usage
import de.gematik.zeta.sdk.crypto.openssl.OBJ_obj2txt
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_STACK
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_free
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_new_null
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_num
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_push
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_value
import de.gematik.zeta.sdk.crypto.openssl.X509
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_CTX_free
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_CTX_get_error
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_CTX_init
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_CTX_new
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_add_cert
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_free
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_new
import de.gematik.zeta.sdk.crypto.openssl.X509_free
import de.gematik.zeta.sdk.crypto.openssl.X509_get0_notAfter
import de.gematik.zeta.sdk.crypto.openssl.X509_get0_notBefore
import de.gematik.zeta.sdk.crypto.openssl.X509_get_ext_d2i
import de.gematik.zeta.sdk.crypto.openssl.X509_get_pubkey
import de.gematik.zeta.sdk.crypto.openssl.X509_verify_cert
import de.gematik.zeta.sdk.crypto.openssl.X509_verify_cert_error_string
import de.gematik.zeta.sdk.crypto.openssl.d2i_X509
import de.gematik.zeta.sdk.crypto.openssl.i2d_PUBKEY
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
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
import platform.posix.time
import platform.posix.time_t

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual class X509CertValidator actual constructor() {
    actual fun checkValidity(certDer: ByteArray) = memScoped {
        val pCert = alloc<CPointerVar<UByteVar>>()
        pCert.value = certDer.refTo(0).getPointer(this).reinterpret()
        val cert = d2i_X509(null, pCert.ptr, certDer.size.convert())
            ?: error("Failed to parse certificate")

        try {
            val notBefore = X509_get0_notBefore(cert)
                ?: error("Failed to get notBefore")
            val notAfter = X509_get0_notAfter(cert)
                ?: error("Failed to get notAfter")

            val now = time(null)

            val cmpBefore = ASN1_TIME_compare(notBefore, createAsn1Time(now))
            require(cmpBefore <= 0) { "Certificate not yet valid" }

            val cmpAfter = ASN1_TIME_compare(notAfter, createAsn1Time(now))
            require(cmpAfter >= 0) { "Certificate has expired" }

            Log.i { "Certificate validity check passed" }
        } finally {
            X509_free(cert)
        }
    }

    actual fun getExtendedKeyUsage(certDer: ByteArray): List<String> = memScoped {
        val pCert = alloc<CPointerVar<UByteVar>>()
        pCert.value = certDer.refTo(0).getPointer(this).reinterpret()
        val cert = d2i_X509(null, pCert.ptr, certDer.size.convert())
            ?: error("Failed to parse certificate")

        try {
            val eku = X509_get_ext_d2i(cert, NID_ext_key_usage, null, null)
                ?: return emptyList()

            val stack = eku.reinterpret<OPENSSL_STACK>()
            val num = OPENSSL_sk_num(stack)

            buildList {
                for (i in 0 until num) {
                    val obj = OPENSSL_sk_value(stack, i)?.reinterpret<ASN1_OBJECT>()
                        ?: continue

                    val oidLen = OBJ_obj2txt(null, 0, obj, 1)
                    if (oidLen > 0) {
                        val oidBuffer = allocArray<ByteVar>(oidLen + 1)
                        OBJ_obj2txt(oidBuffer, oidLen + 1, obj, 1)
                        add(oidBuffer.toKString())
                    }
                }
            }
        } finally {
            X509_free(cert)
        }
    }

    actual fun getPublicKey(certDer: ByteArray): ByteArray = memScoped {
        val pCert = alloc<CPointerVar<UByteVar>>()
        pCert.value = certDer.refTo(0).getPointer(this).reinterpret()
        val cert = d2i_X509(null, pCert.ptr, certDer.size.convert())
            ?: error("Failed to parse certificate")

        try {
            val pubKey = X509_get_pubkey(cert)
                ?: error("Failed to get public key")

            try {
                val len = i2d_PUBKEY(pubKey, null)
                require(len > 0) { "Failed to get public key size" }

                val buffer = allocArray<UByteVar>(len)
                val pBuffer = alloc<CPointerVar<UByteVar>>()
                pBuffer.value = buffer

                i2d_PUBKEY(pubKey, pBuffer.ptr)

                buffer.readBytes(len)
            } finally {
                EVP_PKEY_free(pubKey)
            }
        } finally {
            X509_free(cert)
        }
    }

    actual fun validateCertChain(
        chainDer: List<ByteArray>,
        trustAnchorsDer: List<ByteArray>,
    ) = memScoped {
        require(chainDer.isNotEmpty()) { "Certificate chain is empty" }

        val store = X509_STORE_new()
            ?: error("Failed to create X509 store")

        try {
            for (anchorDer in trustAnchorsDer) {
                val pAnchor = alloc<CPointerVar<UByteVar>>()
                pAnchor.value = anchorDer.refTo(0).getPointer(this).reinterpret()
                val anchor = d2i_X509(null, pAnchor.ptr, anchorDer.size.convert())
                    ?: error("Failed to parse trust anchor")

                X509_STORE_add_cert(store, anchor)
                X509_free(anchor)
            }

            val pLeaf = alloc<CPointerVar<UByteVar>>()
            pLeaf.value = chainDer[0].refTo(0).getPointer(this).reinterpret()
            val leafCert = d2i_X509(null, pLeaf.ptr, chainDer[0].size.convert())
                ?: error("Failed to parse leaf certificate")

            try {
                val chain = OPENSSL_sk_new_null()
                    ?: error("Failed to create certificate chain")

                try {
                    for (i in 1 until chainDer.size) {
                        val pCert = alloc<CPointerVar<UByteVar>>()
                        pCert.value = chainDer[i].refTo(0).getPointer(this).reinterpret()
                        val cert = d2i_X509(null, pCert.ptr, chainDer[i].size.convert())
                            ?: error("Failed to parse intermediate certificate")

                        OPENSSL_sk_push(chain, cert)
                    }

                    val ctx = X509_STORE_CTX_new()
                        ?: error("Failed to create verification context")

                    try {
                        X509_STORE_CTX_init(ctx, store, leafCert, chain.reinterpret())

                        val result = X509_verify_cert(ctx)
                        if (result != 1) {
                            val error = X509_STORE_CTX_get_error(ctx)
                            val errorStr = X509_verify_cert_error_string(error.convert())?.toKString() ?: "Unknown error"
                            error("Certificate chain validation failed: $errorStr (code: $error)")
                        }

                        Log.i { "Certificate chain validation passed" }
                    } finally {
                        X509_STORE_CTX_free(ctx)
                    }
                } finally {
                    val num = OPENSSL_sk_num(chain)
                    for (i in 0 until num) {
                        val cert = OPENSSL_sk_value(chain, i)?.reinterpret<X509>()
                        X509_free(cert)
                    }
                    OPENSSL_sk_free(chain)
                }
            } finally {
                X509_free(leafCert)
            }
        } finally {
            X509_STORE_free(store)
        }
    }

    private fun createAsn1Time(timeT: time_t): CPointer<ASN1_TIME>? {
        return ASN1_TIME_set(null, timeT)
    }
}
