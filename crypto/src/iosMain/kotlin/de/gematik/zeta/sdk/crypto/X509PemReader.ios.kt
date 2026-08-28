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
import kotlinx.cinterop.readBytes // NOSONAR false positive - is required
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.IOException
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

actual class X509PemReader actual constructor() {

    actual fun loadCertificateFromBytes(
        data: ByteArray,
        alias: String,
        password: String,
    ): ByteArray = memScoped {
        val bio = BIO_new_mem_buf(data.refTo(0), data.size)
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

    actual fun loadCertificate(p12File: String, alias: String, password: String): ByteArray {
        val name = p12File.substringAfterLast("/").substringBeforeLast(".")
        val ext = p12File.substringAfterLast(".")

        val bundlePath = NSBundle.mainBundle.pathForResource(name, ofType = ext)
            ?: error("File not found in bundle: $p12File")

        val p12Bytes = NSData.dataWithContentsOfFile(bundlePath)
            ?.toByteArray()
            ?: error("Failed to read file at path: $bundlePath")

        return loadCertificateFromBytes(p12Bytes, alias, password)
    }

    actual fun loadPrivateKey(p12File: String, alias: String, password: String): ByteArray {
        val name = p12File.substringAfterLast("/").substringBeforeLast(".")
        val ext = p12File.substringAfterLast(".")

        val bundlePath = NSBundle.mainBundle.pathForResource(name, ofType = ext)
            ?: error("File not found in bundle: $p12File")

        val p12Bytes = NSData.dataWithContentsOfFile(bundlePath)
            ?.toByteArray()
            ?: error("Failed to read file at path: $bundlePath")

        return loadPrivateKeyFromBytes(p12Bytes, alias, password)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val result = ByteArray(length.toInt())
        result.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), bytes, length)
        }
        return result
    }

    actual fun loadPrivateKeyFromBytes(data: ByteArray, alias: String, password: String): ByteArray = memScoped {
        val bio = BIO_new_mem_buf(data.refTo(0), data.size)
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
        for (i in 0 until asn1TypeNum(seq)) {
            val node = asn1TypeValue(seq, i) ?: continue
            val result = when (node.pointed.type) {
                V_ASN1_OBJECT -> handleOidNode(node, parent, targetOid)
                V_ASN1_SEQUENCE -> handleSequenceNode(node, seq, targetOid)
                else -> null
            }
            if (result != null) return result
        }
        return null
    }

    private fun handleOidNode(
        node: CPointer<ASN1_TYPE>,
        parent: CPointer<cnames.structs.stack_st_ASN1_TYPE>?,
        targetOid: String,
    ): String? {
        val obj = node.pointed.value.`object` ?: return null
        val buf = ByteArray(128)
        OBJ_obj2txt(buf.refTo(0), buf.size.convert(), obj, 1)
        if (buf.toKString() != targetOid) return null
        return parent?.let { findPrintableString(it) }
    }

    private fun findPrintableString(parent: CPointer<cnames.structs.stack_st_ASN1_TYPE>): String? {
        for (j in 0 until asn1TypeNum(parent)) {
            val pnode = asn1TypeValue(parent, j) ?: continue
            if (pnode.pointed.type != V_ASN1_PRINTABLESTRING) continue
            val ps = pnode.pointed.value.printablestring ?: continue
            val data = ASN1_STRING_get0_data(ps) ?: continue
            return data.readBytes(ASN1_STRING_length(ps)).toKString()
        }
        return null
    }

    private fun handleSequenceNode(
        node: CPointer<ASN1_TYPE>,
        parent: CPointer<cnames.structs.stack_st_ASN1_TYPE>,
        targetOid: String,
    ): String? {
        val seqString = node.pointed.value.asn1_string ?: return null
        val derPtr = ASN1_STRING_get0_data(seqString)?.reinterpret<UByteVar>() ?: return null
        return memScoped {
            val pDer = alloc<CPointerVar<UByteVar>>()
            pDer.value = derPtr
            val childSeq = d2i_ASN1_SEQUENCE_ANY(null, pDer.ptr, ASN1_STRING_length(seqString).convert())
                ?: return@memScoped null
            val found = findRegistrationNumber(childSeq, parent, targetOid)
            freeSequence(childSeq)
            found
        }
    }

    private fun freeSequence(seq: CPointer<cnames.structs.stack_st_ASN1_TYPE>) {
        for (k in 0 until asn1TypeNum(seq)) {
            asn1TypeValue(seq, k)?.let { ASN1_TYPE_free(it) }
        }
        asn1TypeFree(seq)
    }
}

@Suppress("UNCHECKED_CAST")
private fun asn1TypeNum(
    sk: CValuesRef<cnames.structs.stack_st_ASN1_TYPE>?,
): Int {
    return OPENSSL_sk_num(ossl_check_const_ASN1_TYPE_sk_type(sk))
}

@Suppress("UNCHECKED_CAST")
private fun asn1TypeValue(
    sk: CValuesRef<cnames.structs.stack_st_ASN1_TYPE>?,
    idx: Int,
): CPointer<ASN1_TYPE>? {
    return OPENSSL_sk_value(ossl_check_const_ASN1_TYPE_sk_type(sk), idx) as CPointer<ASN1_TYPE>?
}

@Suppress("UNCHECKED_CAST")
private fun asn1TypeFree(
    sk: CValuesRef<cnames.structs.stack_st_ASN1_TYPE>?,
) {
    return OPENSSL_sk_free(ossl_check_const_ASN1_TYPE_sk_type(sk))
}
