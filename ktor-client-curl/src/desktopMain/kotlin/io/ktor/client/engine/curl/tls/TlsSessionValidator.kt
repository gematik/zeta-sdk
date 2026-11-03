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

package io.ktor.client.engine.curl.tls

import io.ktor.client.engine.curl.ASN1_TIME
import io.ktor.client.engine.curl.ASN1_TIME_to_tm
import io.ktor.client.engine.curl.EVP_PKEY_EC
import io.ktor.client.engine.curl.EVP_PKEY_RSA
import io.ktor.client.engine.curl.EVP_PKEY_base_id
import io.ktor.client.engine.curl.EVP_PKEY_bits
import io.ktor.client.engine.curl.EVP_PKEY_free
import io.ktor.client.engine.curl.GENERAL_NAMES
import io.ktor.client.engine.curl.GENERAL_NAMES_free
import io.ktor.client.engine.curl.NID_subject_alt_name
import io.ktor.client.engine.curl.OBJ_nid2sn
import io.ktor.client.engine.curl.SSL
import io.ktor.client.engine.curl.SSL_CIPHER_get_name
import io.ktor.client.engine.curl.SSL_get_current_cipher
import io.ktor.client.engine.curl.SSL_get_peer_cert_chain
import io.ktor.client.engine.curl.SSL_get_version
import io.ktor.client.engine.curl.X509
import io.ktor.client.engine.curl.X509_NAME_oneline
import io.ktor.client.engine.curl.X509_get0_notAfter
import io.ktor.client.engine.curl.X509_get0_notBefore
import io.ktor.client.engine.curl.X509_get_ext_d2i
import io.ktor.client.engine.curl.X509_get_pubkey
import io.ktor.client.engine.curl.X509_get_signature_nid
import io.ktor.client.engine.curl.X509_get_subject_name
import io.ktor.client.engine.curl.i2d_X509
import io.ktor.client.engine.curl.internal.EasyHandle
import io.ktor.client.engine.curl.internal.PendingRevocationData
import io.ktor.client.engine.curl.internal.getInfo
import io.ktor.client.engine.curl.ktor_general_name_dns
import io.ktor.client.engine.curl.ktor_sk_GENERAL_NAME_num
import io.ktor.client.engine.curl.ktor_sk_GENERAL_NAME_value
import io.ktor.client.engine.curl.ktor_sk_X509_num
import io.ktor.client.engine.curl.ktor_sk_X509_value
import io.ktor.client.engine.curl.ktor_x509_common_name
import io.ktor.http.Url
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import libcurl.CURLINFO_EFFECTIVE_URL
import libcurl.CURLINFO_TLS_SSL_PTR
import libcurl.curl_tlssessioninfo

internal fun validateTlsSession(
    easyHandle: EasyHandle,
    config: TlsValidationConfig? = null,
    staple: ByteArray?,
): PendingRevocationData? = memScoped {
    val ssl = resolveSslPointer(easyHandle) ?: run {
        return@memScoped null
    }
    val protocol = inspectProtocol(ssl)
    val cipherName = inspectCipher(ssl)
    val leafCertInfo = inspectCertChain(ssl) ?: run {
        return@memScoped null
    }
    val host = resolveHost(easyHandle) ?: run {
        return@memScoped null
    }

    try {
        val result = config?.onSessionValidated?.invoke(
            TlsSessionData(
                protocol = protocol,
                cipherSuite = cipherName,
                leafCertInfo = leafCertInfo,
                host = host,
                staple = staple,
            ),
        )
        result
    } catch (e: Throwable) {
        config?.lastError = e
        null
    }
}

private fun resolveHost(easyHandle: EasyHandle): String? = memScoped {
    val urlPtr = alloc<COpaquePointerVar>()
    easyHandle.getInfo(CURLINFO_EFFECTIVE_URL, urlPtr.ptr)
    urlPtr.value?.reinterpret<ByteVar>()?.toKString()
        ?.let { Url(it).host }
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveSslPointer(easyHandle: EasyHandle): CPointer<SSL>? = memScoped {
    val tlsInfoPtr = alloc<COpaquePointerVar>()
    easyHandle.getInfo(CURLINFO_TLS_SSL_PTR, tlsInfoPtr.ptr)

    val tlsSessionInfo = tlsInfoPtr.value?.reinterpret<curl_tlssessioninfo>()?.pointed ?: return null

    val ssl = tlsSessionInfo.internals?.reinterpret<SSL>() ?: return null

    ssl
}

@OptIn(ExperimentalForeignApi::class)
private fun inspectProtocol(ssl: CPointer<SSL>): String? {
    val protocol = SSL_get_version(ssl)?.toKString()
    return protocol
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun inspectCipher(ssl: CPointer<SSL>): String? = memScoped {
    val cipher = SSL_get_current_cipher(ssl)
    val name = cipher?.let { SSL_CIPHER_get_name(it)?.toKString() }

    name
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun inspectCertChain(ssl: CPointer<SSL>): LeafCertInfo? {
    val certChain = SSL_get_peer_cert_chain(ssl) ?: return null
    val numCerts = ktor_sk_X509_num(certChain)
    val chainMeta = mutableListOf<ChainCertMeta>()
    var leafCertInfo: LeafCertInfo? = null

    for (i in 0 until numCerts) {
        val cert = ktor_sk_X509_value(certChain, i) ?: continue
        val (sigAlgSn, subjectDN, notBefore, notAfter) = inspectCertMeta(cert)
        val (keyTypeName, keyBits, curveName) = inspectCertKey(cert)
        val san = extractSan(cert)
        val commonName = ktor_x509_common_name(cert)?.toKString()

        chainMeta.add(ChainCertMeta(keyTypeName, keyBits, sigAlgSn, subjectDN, notBefore, notAfter, curveName, san, commonName))

        if (i == 0) {
            leafCertInfo = LeafCertInfo(
                keyTypeName = keyTypeName,
                keyBits = keyBits,
                sigAlgSn = sigAlgSn,
                subjectDN = subjectDN,
                notBefore = notBefore,
                notAfter = notAfter,
                curveName = curveName,
                certDer = extractDer(cert),
                issuerDer = ktor_sk_X509_value(certChain, 1)?.let { extractDer(it) },
                san = san,
                commonName = commonName,
            )
        }
    }
    return leafCertInfo?.copy(fullChain = chainMeta)
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun extractSan(cert: CPointer<X509>): List<String> = memScoped {
    val sans = X509_get_ext_d2i(cert, NID_subject_alt_name, null, null)
        ?.reinterpret<GENERAL_NAMES>() ?: return emptyList()

    val count = ktor_sk_GENERAL_NAME_num(sans)
    val result = (0 until count).mapNotNull { i ->
        val name = ktor_sk_GENERAL_NAME_value(sans, i) ?: return@mapNotNull null
        ktor_general_name_dns(name)?.toKString()
    }
    GENERAL_NAMES_free(sans)
    result
}

private fun extractDer(cert: CPointer<X509>): ByteArray = memScoped {
    val len = i2d_X509(cert, null)
    if (len <= 0) return byteArrayOf()
    val buf = allocArray<UByteVar>(len)
    val ptr = alloc<CPointerVar<UByteVar>>()
    ptr.value = buf

    i2d_X509(cert, ptr.ptr)

    buf.readBytes(len)
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun inspectCertMeta(cert: CPointer<X509>): CertMeta {
    val subject = X509_get_subject_name(cert)?.let { X509_NAME_oneline(it, null, 0)?.toKString() }
    val notBefore = X509_get0_notBefore(cert)?.toEpochSeconds()
    val notAfter = X509_get0_notAfter(cert)?.toEpochSeconds()
    val sigNid = X509_get_signature_nid(cert)
    val sigAlgSn = OBJ_nid2sn(sigNid)?.toKString()

    return CertMeta(sigAlgSn, subject, notBefore, notAfter)
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun inspectCertKey(cert: CPointer<X509>): CertKey = memScoped {
    val pubKey = X509_get_pubkey(cert) ?: return CertKey(null, null, null)
    val keyType = EVP_PKEY_base_id?.invoke(pubKey)
    val keyBits = EVP_PKEY_bits?.invoke(pubKey)
    val keyTypeName = when (keyType) {
        EVP_PKEY_RSA -> "RSA"
        EVP_PKEY_EC -> "EC"
        else -> "OTHER($keyType)"
    }

    val curveName = if (keyType == EVP_PKEY_EC) {
        when (keyBits) {
            256 -> "secp256r1"
            384 -> "secp384r1"
            521 -> "secp521r1"
            else -> null
        }
    } else {
        null
    }

    EVP_PKEY_free(pubKey)
    CertKey(keyTypeName, keyBits, curveName)
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<ASN1_TIME>.toEpochSeconds(): Long = memScoped {
    val tm = alloc<platform.posix.tm>()
    if (ASN1_TIME_to_tm(this@toEpochSeconds, tm.ptr) != 1) return 0L
    platform.posix.mktime(tm.ptr).toLong()
}

data class LeafCertInfo(
    val keyTypeName: String?,
    val keyBits: Int?,
    val sigAlgSn: String?,
    val subjectDN: String?,
    val notBefore: Long?,
    val notAfter: Long?,
    val curveName: String?,
    val certDer: ByteArray? = null,
    val issuerDer: ByteArray? = null,
    val san: List<String>? = null,
    val commonName: String? = null,
    val fullChain: List<ChainCertMeta>? = null,
)

data class ChainCertMeta(
    val keyTypeName: String?,
    val keyBits: Int?,
    val sigAlgSn: String?,
    val subjectDN: String?,
    val notBefore: Long?,
    val notAfter: Long?,
    val curveName: String?,
    val san: List<String>?,
    val commonName: String? = null,
)

private data class CertMeta(
    val sigAlgSn: String?,
    val subjectDN: String?,
    val notBefore: Long?,
    val notAfter: Long?,
)

private data class CertKey(
    val keyTypeName: String?,
    val keyBits: Int?,
    val curveName: String?,
)
