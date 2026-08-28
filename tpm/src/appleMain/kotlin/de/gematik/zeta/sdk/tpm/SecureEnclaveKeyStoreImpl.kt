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

import de.gematik.zeta.logging.Log
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureDigestX962SHA256
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.Security.kSecUseDataProtectionKeychain

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class SecureEnclaveKeyStoreImpl : SecureEnclaveKeyStore {

    override fun publicKey(tag: String): ByteArray? {
        val key = copyKeyRef(tag) ?: run {
            Log.w { "No Secure Enclave key found for tag=$tag" }
            return null
        }
        return try {
            copyPublicKeyPoint(key) ?: run {
                Log.e { "Public key export failed for existing Secure Enclave key tag=$tag" }
                null
            }
        } finally {
            CFRelease(key)
        }
    }

    override fun createKey(tag: String): ByteArray? {
        val accessControl = createAccessControl(tag) ?: return null
        val tagCf = CFBridgingRetain(tag.toNSData())
        val attributes = createAttributes(tagCf, accessControl)
        val privateKey = createPrivateKey(attributes, tag)
        CFRelease(attributes)
        CFRelease(accessControl)
        CFRelease(tagCf)
        privateKey ?: return null
        return try {
            copyPublicKeyPoint(privateKey) ?: run {
                Log.e { "Public key export failed for newly created Secure Enclave key tag=$tag" }
                null
            }
        } finally {
            CFRelease(privateKey)
        }
    }

    private fun createAccessControl(tag: String): CFTypeRef? = memScoped {
        val error = alloc<CFErrorRefVar>()
        val accessControl = SecAccessControlCreateWithFlags(
            null,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAccessControlPrivateKeyUsage,
            error.ptr,
        )
        if (accessControl == null) {
            val nsError = error.value?.let { CFBridgingRelease(it) as? NSError }
            Log.e {
                "SecAccessControl creation failed for tag=$tag " +
                    "error=${nsError?.localizedDescription} " +
                    "domain=${nsError?.domain} code=${nsError?.code}"
            }
        }
        accessControl
    }

    private fun createAttributes(tagCf: CFTypeRef?, accessControl: CFTypeRef?): CFDictionaryRef {
        val keySizeCf = CFBridgingRetain(NSNumber(int = 256))
        val privateKeyAttrs = cfDictionary(
            kSecAttrIsPermanent to kCFBooleanTrue,
            kSecAttrApplicationTag to tagCf,
            kSecAttrAccessControl to accessControl,
        )
        val attributes = cfDictionary(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to keySizeCf,
            kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs to privateKeyAttrs,
        )
        CFRelease(keySizeCf)
        CFRelease(privateKeyAttrs)
        return attributes
    }

    private fun createPrivateKey(attributes: CFDictionaryRef, tag: String): SecKeyRef? = memScoped {
        val error = alloc<CFErrorRefVar>()
        val privateKey = SecKeyCreateRandomKey(attributes, error.ptr)
        if (privateKey == null) {
            val nsError = error.value?.let { CFBridgingRelease(it) as? NSError }
            Log.e {
                "Secure Enclave key creation unavailable/not permitted for tag=$tag " +
                    "error=${nsError?.localizedDescription} " +
                    "domain=${nsError?.domain} code=${nsError?.code}"
            }
        }
        privateKey
    }

    override fun sign(tag: String, digest: ByteArray): ByteArray {
        val key = copyKeyRef(tag) ?: error("Secure Enclave client key not found for tag=$tag")
        try {
            return memScoped {
                val dataCf = CFBridgingRetain(digest.toNSData())
                val errorVar = alloc<CFErrorRefVar>()
                val sigCf = SecKeyCreateSignature(
                    key,
                    kSecKeyAlgorithmECDSASignatureDigestX962SHA256,
                    dataCf?.reinterpret(),
                    errorVar.ptr,
                )
                CFRelease(dataCf)
                if (sigCf == null) {
                    val nsError = errorVar.value?.let { CFBridgingRelease(it) as? NSError }
                    error(
                        "Secure Enclave signing failed for tag=$tag " +
                            "error=${nsError?.localizedDescription} " +
                            "domain=${nsError?.domain} code=${nsError?.code}",
                    )
                }
                (CFBridgingRelease(sigCf) as NSData).toByteArray()
            }
        } finally {
            CFRelease(key)
        }
    }

    override fun deleteKey(tag: String) {
        val tagCf = CFBridgingRetain(tag.toNSData())
        val query = cfDictionary(
            kSecClass to kSecClassKey,
            kSecAttrApplicationTag to tagCf,
            kSecUseDataProtectionKeychain to kCFBooleanTrue,
        )
        val status = SecItemDelete(query)
        CFRelease(query)
        CFRelease(tagCf)
        when (status) {
            errSecSuccess -> Log.d { "Deleted Secure Enclave key for tag=$tag" }
            errSecItemNotFound -> Log.e { "No Secure Enclave key to delete for tag=$tag" }
            else -> Log.e { "Failed to delete Secure Enclave key for tag=$tag status=$status" }
        }
    }

    private fun copyKeyRef(tag: String): SecKeyRef? = memScoped {
        val tagCf = CFBridgingRetain(tag.toNSData())
        val query = cfDictionary(
            kSecClass to kSecClassKey,
            kSecAttrApplicationTag to tagCf,
            kSecReturnRef to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
            kSecUseDataProtectionKeychain to kCFBooleanTrue,
        )
        val resultVar = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, resultVar.ptr)
        CFRelease(query)
        CFRelease(tagCf)
        when (status) {
            errSecSuccess -> {
                Log.d { "Secure Enclave key found for tag=$tag" }
                resultVar.value?.reinterpret()
            }
            errSecItemNotFound -> {
                Log.e { "No Secure Enclave key found for tag=$tag" }
                null
            }
            else -> {
                Log.e { "Secure Enclave key lookup failed for tag=$tag status=$status" }
                null
            }
        }
    }

    private fun copyPublicKeyPoint(privateKey: SecKeyRef): ByteArray? {
        val publicKey = SecKeyCopyPublicKey(privateKey) ?: return null
        try {
            val cfData = SecKeyCopyExternalRepresentation(publicKey, null) ?: return null
            val bytes = (CFBridgingRelease(cfData) as NSData).toByteArray()
            return if (bytes.size == 65 && bytes[0] == 0x04.toByte()) bytes else null
        } finally {
            CFRelease(publicKey)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cfDictionary(vararg pairs: Pair<Any?, Any?>): CFDictionaryRef = memScoped {
    val keys = allocArrayOf(*Array(pairs.size) { i -> pairs[i].first as CPointer<*>? })
    val values = allocArrayOf(*Array(pairs.size) { i -> pairs[i].second as CPointer<*>? })
    CFDictionaryCreate(
        allocator = null,
        keys = keys.reinterpret(),
        values = values.reinterpret(),
        numValues = pairs.size.convert(),
        keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
        valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: error("CFDictionaryCreate returned null")
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toNSData(): NSData = this.encodeToByteArray().toNSData()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    if (isEmpty()) return NSData()
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.convert())
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val result = ByteArray(length)
    val ptr = this.bytes ?: return ByteArray(0)
    val src = ptr.reinterpret<ByteVar>()
    for (i in 0 until length) result[i] = src[i]
    return result
}
