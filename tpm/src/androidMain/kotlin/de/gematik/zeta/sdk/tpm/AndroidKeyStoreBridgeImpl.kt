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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import de.gematik.zeta.android.baseContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

class AndroidKeyStoreBridgeImpl(
    private val context: () -> Context = { baseContext },
) : AndroidKeyStoreBridge {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun hasHardwareKeystore(): Boolean {
        if (!isAndroidKeyStoreAvailable()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            val pm = context().packageManager
            pm.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE) ||
                pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        }.getOrDefault(false)
    }

    private fun isAndroidKeyStoreAvailable(): Boolean = runCatching { keyStore }.isSuccess

    override fun generateEcKey(alias: String, attestationChallenge: ByteArray?, strongBox: Boolean): KeySecurityLevel {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            // Signs client assertions and client statements in the background (token refresh, push), without fingerprint or PIN.
            .setUserAuthenticationRequired(false)
            .apply { attestationChallenge?.let { setAttestationChallenge(it) } }
            .build()
        val pair = try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
                initialize(spec)
                generateKeyPair()
            }
        } catch (e: StrongBoxUnavailableException) {
            throw StrongBoxUnavailable(e)
        } catch (e: ProviderException) {
            throw if (strongBox) strongBoxFailure(alias, e) else e
        } catch (e: KeyStoreException) {
            throw if (strongBox) strongBoxFailure(alias, e) else e
        }
        return securityLevel(pair.private, strongBox)
    }

    private fun strongBoxFailure(alias: String, cause: Exception): StrongBoxUnavailable {
        try {
            delete(alias)
        } catch (e: KeyStoreException) {
            cause.addSuppressed(e)
        }
        return StrongBoxUnavailable(cause)
    }

    override fun publicKeyPoint(alias: String): ByteArray? =
        (keyStore.getCertificate(alias)?.publicKey as? ECPublicKey)?.let(::ecPublicKeyToPoint)

    override fun certificateChain(alias: String): List<ByteArray>? =
        keyStore.getCertificateChain(alias)?.map { it.encoded }

    override fun signSha256(alias: String, data: ByteArray): ByteArray = sign(alias, "SHA256withECDSA", data)

    override fun contains(alias: String): Boolean = keyStore.containsAlias(alias)

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun sign(alias: String, algorithm: String, input: ByteArray): ByteArray {
        val key = try {
            keyStore.getKey(alias, null) as? PrivateKey
        } catch (e: UnrecoverableKeyException) {
            throw AndroidKeyInvalidated(e)
        } ?: throw AndroidKeyInvalidated(null)
        return try {
            Signature.getInstance(algorithm).run {
                initSign(key)
                update(input)
                sign()
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw AndroidKeyInvalidated(e)
        }
    }

    private fun securityLevel(key: PrivateKey, strongBoxRequested: Boolean): KeySecurityLevel {
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                -> KeySecurityLevel.TRUSTED_ENVIRONMENT
                else -> KeySecurityLevel.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            when {
                !info.isInsideSecureHardware -> KeySecurityLevel.SOFTWARE
                strongBoxRequested -> KeySecurityLevel.STRONGBOX
                else -> KeySecurityLevel.TRUSTED_ENVIRONMENT
            }
        }
    }
}
