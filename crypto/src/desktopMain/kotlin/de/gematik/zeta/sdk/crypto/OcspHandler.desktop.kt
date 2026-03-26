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
import de.gematik.zeta.sdk.crypto.openssl.ACCESS_DESCRIPTION
import de.gematik.zeta.sdk.crypto.openssl.ASN1_GENERALIZEDTIME
import de.gematik.zeta.sdk.crypto.openssl.ASN1_STRING_get0_data
import de.gematik.zeta.sdk.crypto.openssl.DIST_POINT
import de.gematik.zeta.sdk.crypto.openssl.EVP_PKEY_free
import de.gematik.zeta.sdk.crypto.openssl.GENERAL_NAME
import de.gematik.zeta.sdk.crypto.openssl.GEN_URI
import de.gematik.zeta.sdk.crypto.openssl.NID_ad_OCSP
import de.gematik.zeta.sdk.crypto.openssl.NID_crl_distribution_points
import de.gematik.zeta.sdk.crypto.openssl.NID_info_access
import de.gematik.zeta.sdk.crypto.openssl.OBJ_obj2nid
import de.gematik.zeta.sdk.crypto.openssl.OCSP_BASICRESP_free
import de.gematik.zeta.sdk.crypto.openssl.OCSP_CERTID_free
import de.gematik.zeta.sdk.crypto.openssl.OCSP_REQUEST_free
import de.gematik.zeta.sdk.crypto.openssl.OCSP_REQUEST_new
import de.gematik.zeta.sdk.crypto.openssl.OCSP_RESPONSE_STATUS_SUCCESSFUL
import de.gematik.zeta.sdk.crypto.openssl.OCSP_RESPONSE_free
import de.gematik.zeta.sdk.crypto.openssl.OCSP_basic_verify
import de.gematik.zeta.sdk.crypto.openssl.OCSP_cert_to_id
import de.gematik.zeta.sdk.crypto.openssl.OCSP_request_add0_id
import de.gematik.zeta.sdk.crypto.openssl.OCSP_request_add1_nonce
import de.gematik.zeta.sdk.crypto.openssl.OCSP_resp_find_status
import de.gematik.zeta.sdk.crypto.openssl.OCSP_resp_get0_produced_at
import de.gematik.zeta.sdk.crypto.openssl.OCSP_response_get1_basic
import de.gematik.zeta.sdk.crypto.openssl.OCSP_response_status
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_STACK
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_num
import de.gematik.zeta.sdk.crypto.openssl.OPENSSL_sk_value
import de.gematik.zeta.sdk.crypto.openssl.V_OCSP_CERTSTATUS_GOOD
import de.gematik.zeta.sdk.crypto.openssl.X509
import de.gematik.zeta.sdk.crypto.openssl.X509_CRL_free
import de.gematik.zeta.sdk.crypto.openssl.X509_CRL_get0_by_cert
import de.gematik.zeta.sdk.crypto.openssl.X509_CRL_verify
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_add_cert
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_free
import de.gematik.zeta.sdk.crypto.openssl.X509_STORE_new
import de.gematik.zeta.sdk.crypto.openssl.X509_free
import de.gematik.zeta.sdk.crypto.openssl.X509_get_ext_d2i
import de.gematik.zeta.sdk.crypto.openssl.X509_get_pubkey
import de.gematik.zeta.sdk.crypto.openssl.d2i_OCSP_RESPONSE
import de.gematik.zeta.sdk.crypto.openssl.d2i_X509
import de.gematik.zeta.sdk.crypto.openssl.d2i_X509_CRL
import de.gematik.zeta.sdk.crypto.openssl.i2d_OCSP_REQUEST
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.mktime
import platform.posix.tm

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual class OcspHandler {
    actual fun getProducedAtEpochSeconds(ocspResponseDer: ByteArray): Long = memScoped {
        val pData = alloc<CPointerVar<UByteVar>>()
        pData.value = ocspResponseDer.refTo(0).getPointer(this).reinterpret()

        val ocspResp = d2i_OCSP_RESPONSE(null, pData.ptr, ocspResponseDer.size.convert())
            ?: error("Failed to parse OCSP response")

        try {
            val basicResp = OCSP_response_get1_basic(ocspResp)
                ?: error("Failed to get basic OCSP response")

            try {
                val producedAt = OCSP_resp_get0_produced_at(basicResp)
                    ?: error("Failed to get producedAt")

                val timeStr = ASN1_STRING_get0_data(producedAt.reinterpret())?.reinterpret<ByteVar>()?.toKString()
                    ?: error("Failed to get time string")

                parseGeneralizedTime(timeStr)
            } finally {
                OCSP_BASICRESP_free(basicResp)
            }
        } finally {
            OCSP_RESPONSE_free(ocspResp)
        }
    }

    actual fun validate(
        ocspResponseDer: ByteArray,
        certDer: ByteArray,
        issuerDer: ByteArray,
    ) = memScoped {
        val pData = alloc<CPointerVar<UByteVar>>()
        pData.value = ocspResponseDer.refTo(0).getPointer(this).reinterpret()

        val ocspResp = d2i_OCSP_RESPONSE(null, pData.ptr, ocspResponseDer.size.convert())
            ?: error("Failed to parse OCSP response")

        try {
            val status = OCSP_response_status(ocspResp)
            require(status == OCSP_RESPONSE_STATUS_SUCCESSFUL) {
                "OCSP response status not successful: $status"
            }

            val basicResp = OCSP_response_get1_basic(ocspResp)
                ?: error("Failed to get basic OCSP response")

            try {
                val pCert = alloc<CPointerVar<UByteVar>>()
                pCert.value = certDer.refTo(0).getPointer(this).reinterpret()
                val cert = d2i_X509(null, pCert.ptr, certDer.size.convert())
                    ?: error("Failed to parse certificate")

                val pIssuer = alloc<CPointerVar<UByteVar>>()
                pIssuer.value = issuerDer.refTo(0).getPointer(this).reinterpret()
                val issuer = d2i_X509(null, pIssuer.ptr, issuerDer.size.convert())
                    ?: error("Failed to parse issuer")

                try {
                    val store = X509_STORE_new() ?: error("Failed to create X509 store")
                    try {
                        X509_STORE_add_cert(store, issuer)
                        val verifyResult = OCSP_basic_verify(basicResp, null, store, 0u)
                        require(verifyResult == 1) { "OCSP signature verification failed" }
                    } finally {
                        X509_STORE_free(store)
                    }

                    val certId = OCSP_cert_to_id(null, cert, issuer)
                        ?: error("Failed to create certificate ID")

                    try {
                        val statusPtr = alloc<IntVar>()
                        val reasonPtr = alloc<IntVar>()
                        val revTimePtr = allocPointerTo<ASN1_GENERALIZEDTIME>()
                        val thisUpdPtr = allocPointerTo<ASN1_GENERALIZEDTIME>()
                        val nextUpdPtr = allocPointerTo<ASN1_GENERALIZEDTIME>()

                        val found = OCSP_resp_find_status(basicResp, certId, statusPtr.ptr, reasonPtr.ptr, revTimePtr.ptr, thisUpdPtr.ptr, nextUpdPtr.ptr)
                        require(found == 1) { "Certificate not found in OCSP response" }
                        require(statusPtr.value == V_OCSP_CERTSTATUS_GOOD) { "Certificate revoked" }
                    } finally {
                        OCSP_CERTID_free(certId)
                    }
                } finally {
                    X509_free(cert)
                    X509_free(issuer)
                }
            } finally {
                OCSP_BASICRESP_free(basicResp)
            }
        } finally {
            OCSP_RESPONSE_free(ocspResp)
        }
    }

    actual suspend fun prepareOcspRequest(certDer: ByteArray, issuerDer: ByteArray): OcspRequestData = memScoped {
        val pCert = alloc<CPointerVar<UByteVar>>()
        pCert.value = certDer.refTo(0).getPointer(this).reinterpret()
        val cert = d2i_X509(null, pCert.ptr, certDer.size.convert())
            ?: error("Failed to parse certificate")

        val pIssuer = alloc<CPointerVar<UByteVar>>()
        pIssuer.value = issuerDer.refTo(0).getPointer(this).reinterpret()
        val issuer = d2i_X509(null, pIssuer.ptr, issuerDer.size.convert())
            ?: error("Failed to parse issuer")

        try {
            val ocspUrl = extractOcspUrl(cert) ?: error("No OCSP URL")
            val ocspReq = OCSP_REQUEST_new() ?: error("Failed to create OCSP request")

            try {
                val certId = OCSP_cert_to_id(null, cert, issuer) ?: error("Failed to create certificate ID")
                OCSP_request_add0_id(ocspReq, certId)
                OCSP_request_add1_nonce(ocspReq, null, -1)

                val len = i2d_OCSP_REQUEST(ocspReq, null)
                val buffer = allocArray<UByteVar>(len)
                val pBuffer = alloc<CPointerVar<UByteVar>>()
                pBuffer.value = buffer
                i2d_OCSP_REQUEST(ocspReq, pBuffer.ptr)

                OcspRequestData(url = ocspUrl, requestDer = buffer.readBytes(len))
            } finally {
                OCSP_REQUEST_free(ocspReq)
            }
        } finally {
            X509_free(cert)
            X509_free(issuer)
        }
    }

    private fun extractOcspUrl(cert: CPointer<X509>): String? = memScoped {
        val aia = X509_get_ext_d2i(cert, NID_info_access, null, null) ?: return null
        val stack = aia.reinterpret<OPENSSL_STACK>()
        val num = OPENSSL_sk_num(stack)

        for (i in 0 until num) {
            val ad = OPENSSL_sk_value(stack, i)?.reinterpret<ACCESS_DESCRIPTION>() ?: continue
            if (OBJ_obj2nid(ad.pointed.method) == NID_ad_OCSP) {
                val location = ad.pointed.location
                if (location?.pointed?.type == GEN_URI) {
                    return ASN1_STRING_get0_data(location.pointed.d.ia5?.reinterpret())?.reinterpret<ByteVar>()?.toKString()
                }
            }
        }
        null
    }

    private fun parseGeneralizedTime(timeStr: String): Long {
        val year = if (timeStr.length >= 14) timeStr.substring(0, 4).toInt() else 2000 + timeStr.substring(0, 2).toInt()
        val offset = if (timeStr.length >= 14) 4 else 2
        val tm = nativeHeap.alloc<tm>().apply {
            tm_year = year - 1900
            tm_mon = timeStr.substring(offset, offset + 2).toInt() - 1
            tm_mday = timeStr.substring(offset + 2, offset + 4).toInt()
            tm_hour = timeStr.substring(offset + 4, offset + 6).toInt()
            tm_min = timeStr.substring(offset + 6, offset + 8).toInt()
            tm_sec = timeStr.substring(offset + 8, offset + 10).toInt()
        }
        val epoch = mktime(tm.ptr)
        nativeHeap.free(tm)
        return epoch
    }

    actual fun extractCrlUrl(certDer: ByteArray): String? = memScoped {
        val pCert = alloc<CPointerVar<UByteVar>>()
        pCert.value = certDer.refTo(0).getPointer(this).reinterpret()
        val cert = d2i_X509(null, pCert.ptr, certDer.size.convert())
            ?: return null

        try {
            val crlDp = X509_get_ext_d2i(cert, NID_crl_distribution_points, null, null)
                ?: return null

            val stack = crlDp.reinterpret<OPENSSL_STACK>()
            val num = OPENSSL_sk_num(stack)

            for (i in 0 until num) {
                val dp = OPENSSL_sk_value(stack, i)?.reinterpret<DIST_POINT>() ?: continue
                val dpName = dp.pointed.distpoint ?: continue

                if (dpName.pointed.type == 0) {
                    val genNames = dpName.pointed.name.fullname?.reinterpret<OPENSSL_STACK>()
                    if (genNames != null) {
                        val gnNum = OPENSSL_sk_num(genNames)

                        for (j in 0 until gnNum) {
                            val genName = OPENSSL_sk_value(genNames, j)?.reinterpret<GENERAL_NAME>() ?: continue
                            if (genName.pointed.type == GEN_URI) {
                                val uri = ASN1_STRING_get0_data(genName.pointed.d.ia5?.reinterpret())?.reinterpret<ByteVar>()?.toKString()
                                if (uri != null) return uri
                            }
                        }
                    }
                }
            }
            null
        } finally {
            X509_free(cert)
        }
    }

    actual fun validateCrl(crlDer: ByteArray, certDer: ByteArray, issuerDer: ByteArray) = memScoped {
        val pCrl = alloc<CPointerVar<UByteVar>>()
        pCrl.value = crlDer.refTo(0).getPointer(this).reinterpret()
        val crl = d2i_X509_CRL(null, pCrl.ptr, crlDer.size.convert())
            ?: error("Failed to parse CRL")

        try {
            val pCert = alloc<CPointerVar<UByteVar>>()
            pCert.value = certDer.refTo(0).getPointer(this).reinterpret()
            val cert = d2i_X509(null, pCert.ptr, certDer.size.convert())
                ?: error("Failed to parse certificate")

            val pIssuer = alloc<CPointerVar<UByteVar>>()
            pIssuer.value = issuerDer.refTo(0).getPointer(this).reinterpret()
            val issuer = d2i_X509(null, pIssuer.ptr, issuerDer.size.convert())
                ?: error("Failed to parse issuer")

            try {
                val issuerPubKey = X509_get_pubkey(issuer)
                    ?: error("Failed to get issuer public key")

                try {
                    val verifyResult = X509_CRL_verify(crl, issuerPubKey)
                    require(verifyResult == 1) { "CRL signature verification failed" }
                } finally {
                    EVP_PKEY_free(issuerPubKey)
                }

                val revoked = X509_CRL_get0_by_cert(crl, null, cert)
                require(revoked == 0) { "Certificate is REVOKED" }

                Log.i { "Certificate not found in CRL - status OK" }
            } finally {
                X509_free(cert)
                X509_free(issuer)
            }
        } finally {
            X509_CRL_free(crl)
        }
    }
}
