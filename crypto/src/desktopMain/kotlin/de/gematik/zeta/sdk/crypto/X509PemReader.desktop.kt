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

@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package de.gematik.zeta.sdk.crypto

import de.gematik.zeta.sdk.crypto.openssl.ASN1_STRING_get0_data
import de.gematik.zeta.sdk.crypto.openssl.ASN1_STRING_length
import de.gematik.zeta.sdk.crypto.openssl.ASN1_TYPE
import de.gematik.zeta.sdk.crypto.openssl.ASN1_TYPE_free
import de.gematik.zeta.sdk.crypto.openssl.BIO_free
import de.gematik.zeta.sdk.crypto.openssl.BIO_new_mem_buf
import de.gematik.zeta.sdk.crypto.openssl.CRYPTO_free
import de.gematik.zeta.sdk.crypto.openssl.ERR_error_string
import de.gematik.zeta.sdk.crypto.openssl.ERR_get_error
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY2PKCS8
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_free
import de.gematik.zeta.sdk.crypto.openssl.OBJ_obj2txt
import de.gematik.zeta.sdk.crypto.openssl.OBJ_txt2obj
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_free
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_num
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_value
import de.gematik.zeta.sdk.crypto.openssl.PKCS12_free
import de.gematik.zeta.sdk.crypto.openssl.PKCS12_parse
import de.gematik.zeta.sdk.crypto.openssl.PKCS8_PRIV_KEY_INFO_free
import de.gematik.zeta.sdk.crypto.openssl.V_ASN1_OBJECT
import de.gematik.zeta.sdk.crypto.openssl.V_ASN1_PRINTABLESTRING
import de.gematik.zeta.sdk.crypto.openssl.V_ASN1_SEQUENCE
import de.gematik.zeta.sdk.crypto.openssl.X509
import de.gematik.zeta.sdk.crypto.openssl.X509_EXTENSION_get_data
import de.gematik.zeta.sdk.crypto.openssl.X509_free
import de.gematik.zeta.sdk.crypto.openssl.X509_get_ext
import de.gematik.zeta.sdk.crypto.openssl.X509_get_ext_by_OBJ
import de.gematik.zeta.sdk.crypto.openssl.d2i_ASN1_SEQUENCE_ANY
import de.gematik.zeta.sdk.crypto.openssl.d2i_ASN1_TYPE
import de.gematik.zeta.sdk.crypto.openssl.d2i_PKCS12_bio
import de.gematik.zeta.sdk.crypto.openssl.d2i_X509
import de.gematik.zeta.sdk.crypto.openssl.i2d_PKCS8_PRIV_KEY_INFO
import de.gematik.zeta.sdk.crypto.openssl.i2d_X509
import de.gematik.zeta.sdk.crypto.openssl.ossl_check_const_ASN1_TYPE_sk_type
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

actual class X509PemReader {

    actual fun loadCertificate(p12File: String, alias: String, password: String): ByteArray = memScoped {
        val p12Bytes = FileSystem.SYSTEM.source(p12File.toPath()).use { it.buffer().readByteArray() }

        val bio = BIO_new_mem_buf(p12Bytes.refTo(0), p12Bytes.size)
        if (bio == null) throw IOException("BIO_new_mem_buf: bio == $bio")

        val p12 = d2i_PKCS12_bio(bio, null)
        BIO_free(bio)
        if (p12 == null) throw IOException("d2i_PKCS12_bio: p12 == $p12, ${getOpenSSLErrors()}")

        val certPtr = alloc<CPointerVar<X509>>()
        val keyPtr = alloc<CPointerVar<EVP_PKEY>>()
        val success = PKCS12_parse(p12, password, keyPtr.ptr, certPtr.ptr, null)
        PKCS12_free(p12)
        EVP_PKEY_free(keyPtr.value)
        if (success != 1) throw IOException("PKCS12_parse: success == $success, ${getOpenSSLErrors()}")

        val outPtrVar = alloc<CPointerVar<UByteVar>>()
        val len = i2d_X509(certPtr.value, outPtrVar.ptr)
        X509_free(certPtr.value)
        if (len <= 0) throw IOException("i2d_X509: len == $len ${getOpenSSLErrors()}")

        outPtrVar.value!!.readBytes(len)
    }

    actual fun loadPrivateKey(p12File: String, alias: String, password: String): ByteArray = memScoped {
        val p12Bytes = FileSystem.SYSTEM.source(p12File.toPath()).use { it.buffer().readByteArray() }

        val bio = BIO_new_mem_buf(p12Bytes.refTo(0), p12Bytes.size)
        if (bio == null) throw IOException("BIO_new_mem_buf: bio == $bio")

        val p12 = d2i_PKCS12_bio(bio, null)
        BIO_free(bio)
        if (p12 == null) throw IOException("d2i_PKCS12_bio: p12 == $p12, ${getOpenSSLErrors()}")

        val certPtr = alloc<CPointerVar<X509>>()
        val keyPtr = alloc<CPointerVar<EVP_PKEY>>()
        val success = PKCS12_parse(p12, password, keyPtr.ptr, certPtr.ptr, null)
        PKCS12_free(p12)
        X509_free(certPtr.value)
        if (success != 1) throw IOException("PKCS12_parse: success == $success, ${getOpenSSLErrors()}")

        val p8 = EVP_PKEY2PKCS8(keyPtr.value)
        EVP_PKEY_free(keyPtr.value)
        if (p8 == null) throw IOException("EVP_PKEY2PKCS8: p8 == $p8 ${getOpenSSLErrors()}")

        val outPtrVar = alloc<CPointerVar<UByteVar>>()
        outPtrVar.value = null
        val len = i2d_PKCS8_PRIV_KEY_INFO(p8, outPtrVar.ptr)
        PKCS8_PRIV_KEY_INFO_free(p8)
        if (len <= 0) throw IOException("i2d_PKCS8_PRIV_KEY_INFO: p8 == $p8 ${getOpenSSLErrors()}")

        outPtrVar.value!!.readBytes(len)
    }

    actual fun getRegistrationNumber(certificateBytes: ByteArray): String? = memScoped {
        val ptrVar = alloc<CPointerVar<UByteVar>>()
        certificateBytes.asUByteArray().usePinned { ptrVar.value = it.addressOf(0) }
        val cert = d2i_X509(null, ptrVar.ptr, certificateBytes.size.convert())
        if (cert == null) throw IOException("d2i_X509: certPtr == $cert, ${getOpenSSLErrors()}")

        val extOid = OBJ_txt2obj("1.3.36.8.3.3", 1)
        if (extOid == null) throw IOException("OBJ_txt2obj: extOid == $extOid")

        val extIndex = X509_get_ext_by_OBJ(cert, extOid, -1)
        if (extIndex < 0) throw IOException("X509_get_ext_by_OBJ: extIndex == $extIndex")

        val ext = X509_get_ext(cert, extIndex)
        if (ext == null) throw IOException("X509_get_ext: ext == $ext")

        val octet = X509_EXTENSION_get_data(ext)
        if (octet == null) throw IOException("X509_EXTENSION_get_data: octet == $octet")

        val der = ASN1_STRING_get0_data(octet)
        if (der == null) throw IOException("ASN1_STRING_get0_data: der == $der")

        val len = ASN1_STRING_length(octet)
        if (len <= 0) throw IOException("ASN1_STRING_length: len == $len")

        val pDer = alloc<CPointerVar<UByteVar>>()
        pDer.value = der

        val asn1 = d2i_ASN1_TYPE(null, pDer.ptr, len.convert())

        val seqString = asn1!!.pointed.value.asn1_string
        val derPtr = ASN1_STRING_get0_data(seqString)
        val plen = ASN1_STRING_length(seqString)

        val ppDer = alloc<CPointerVar<UByteVar>>()
        ppDer.value = derPtr

        val childSeq = d2i_ASN1_SEQUENCE_ANY(null, ppDer.ptr, plen.convert())
        findRegistrationNumber(childSeq, null, "1.2.276.0.76.4.50")
    }

    fun findRegistrationNumber(
        seq: CPointer<cnames.structs.stack_st_ASN1_TYPE>?,
        parent: CPointer<cnames.structs.stack_st_ASN1_TYPE>?,
        targetOid: String,
    ): String? {
        if (seq == null) return null

        val count = sk_ASN1_TYPE_num(seq)
        for (i in 0 until count) {
            val node = sk_ASN1_TYPE_value(seq, i) ?: continue

            when (node.pointed.type) {
                V_ASN1_OBJECT -> {
                    val obj = node.pointed.value.`object` ?: continue
                    val buf = ByteArray(128)
                    OBJ_obj2txt(buf.refTo(0), buf.size.convert(), obj, 1)
                    val oidStr = buf.toKString()

                    if (oidStr == targetOid) {
                        if (parent != null) {
                            val pcount = sk_ASN1_TYPE_num(parent)
                            for (j in 0 until pcount) {
                                val pnode = sk_ASN1_TYPE_value(parent, j) ?: continue
                                if (pnode.pointed.type == V_ASN1_PRINTABLESTRING) {
                                    val ps = pnode.pointed.value.printablestring ?: continue
                                    val len = ASN1_STRING_length(ps).toInt()
                                    val data = ASN1_STRING_get0_data(ps) ?: continue
                                    return data.readBytes(len).toKString()
                                }
                            }
                        }
                    }
                }

                V_ASN1_SEQUENCE -> {
                    val seqString = node.pointed.value.asn1_string ?: continue
                    val derPtr = ASN1_STRING_get0_data(seqString)?.reinterpret<UByteVar>() ?: continue
                    val len = ASN1_STRING_length(seqString)

                    memScoped {
                        val pDer = alloc<CPointerVar<UByteVar>>()
                        pDer.value = derPtr

                        val childSeq = d2i_ASN1_SEQUENCE_ANY(null, pDer.ptr, len.convert())
                        if (childSeq != null) {
                            val found = findRegistrationNumber(childSeq, seq, targetOid)

                            val childCount = sk_ASN1_TYPE_num(childSeq)
                            for (k in 0 until childCount) {
                                val cnode = sk_ASN1_TYPE_value(childSeq, k)
                                if (cnode != null) ASN1_TYPE_free(cnode)
                            }

                            sk_ASN1_TYPE_free(childSeq)

                            if (found != null) return found
                        }
                    }
                }
            }
        }

        return null
    }
}

fun getOpenSSLErrors(): String {
    val sb = StringBuilder()
    while (true) {
        val errCode = ERR_get_error()
        if (errCode.convert<ULong>() == 0UL) break
        val errStr = ERR_error_string(errCode, null)?.toKString()
        if (errStr != null) sb.append(errStr).append("\n")
    }
    return sb.toString()
}

@Suppress("FunctionNaming")
private fun OPENSSL_free(ptr: CPointer<*>?) {
    CRYPTO_free(ptr, "", 0)
}

@Suppress("UNCHECKED_CAST", "FunctionNaming")
private fun sk_ASN1_TYPE_num(
    sk: CValuesRef<cnames.structs.stack_st_ASN1_TYPE>?,
): Int {
    return OPENSSL_sk_num(ossl_check_const_ASN1_TYPE_sk_type(sk))
}

@Suppress("UNCHECKED_CAST", "FunctionNaming")
private fun sk_ASN1_TYPE_value(
    sk: CValuesRef<cnames.structs.stack_st_ASN1_TYPE>?,
    idx: Int,
): CPointer<ASN1_TYPE>? {
    return OPENSSL_sk_value(ossl_check_const_ASN1_TYPE_sk_type(sk), idx) as CPointer<ASN1_TYPE>?
}

@Suppress("UNCHECKED_CAST", "FunctionNaming")
private fun sk_ASN1_TYPE_free(
    sk: CValuesRef<cnames.structs.stack_st_ASN1_TYPE>?,
) {
    return OPENSSL_sk_free(ossl_check_const_ASN1_TYPE_sk_type(sk))
}
