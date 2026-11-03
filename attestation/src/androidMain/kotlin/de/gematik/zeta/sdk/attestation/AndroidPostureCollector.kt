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
package de.gematik.zeta.sdk.attestation

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import de.gematik.zeta.android.baseContext
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.attestation.model.AndroidBiometricManagerInfo
import de.gematik.zeta.sdk.attestation.model.AndroidBuild
import de.gematik.zeta.sdk.attestation.model.AndroidBuildVersion
import de.gematik.zeta.sdk.attestation.model.AndroidDevicePolicyManagerInfo
import de.gematik.zeta.sdk.attestation.model.AndroidKeyguardManagerInfo
import de.gematik.zeta.sdk.attestation.model.AndroidPackageManagerInfo
import de.gematik.zeta.sdk.attestation.model.AndroidRo
import de.gematik.zeta.sdk.attestation.model.AndroidRoCrypto

private const val MODULE_METADATA_PACKAGE = "com.google.android.modulemetadata"
private const val UNKNOWN = "unknown"

data class AndroidDeviceInfo(
    val build: AndroidBuild,
    val ro: AndroidRo?,
    val packageManager: AndroidPackageManagerInfo?,
    val keyguardManager: AndroidKeyguardManagerInfo?,
    val biometricManager: AndroidBiometricManagerInfo?,
    val devicePolicyManager: AndroidDevicePolicyManagerInfo?,
)

fun interface AndroidPostureCollector {
    fun collect(): AndroidDeviceInfo
}

internal fun mapPasswordComplexity(value: Int): Int? = when (value) {
    DevicePolicyManager.PASSWORD_COMPLEXITY_NONE -> 0
    DevicePolicyManager.PASSWORD_COMPLEXITY_LOW -> 1
    DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM -> 2
    DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH -> 3
    else -> null
}

internal fun isStorageEncrypted(status: Int): Boolean =
    status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
        status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER

// Client-Statement-mit-Android-Attestation (03) (04) posture data from the Android OS
fun collectAndroidDeviceInfo(): AndroidDeviceInfo {
    val context = baseContext
    val pm = context.packageManager
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    return AndroidDeviceInfo(
        build = AndroidBuild(
            version = AndroidBuildVersion(Build.VERSION.SDK_INT, Build.VERSION.SECURITY_PATCH ?: UNKNOWN),
            manufacturer = Build.MANUFACTURER ?: UNKNOWN,
            product = Build.PRODUCT ?: UNKNOWN,
            model = Build.MODEL ?: UNKNOWN,
            board = Build.BOARD ?: UNKNOWN,
        ),
        ro = AndroidRo(
            crypto = dpm?.let { AndroidRoCrypto(isStorageEncrypted(it.storageEncryptionStatus)) },
            product = null,
        ).takeIf { it.crypto != null || it.product != null },
        packageManager = AndroidPackageManagerInfo(
            featureVerifiedBoot = pm.hasSystemFeature(PackageManager.FEATURE_VERIFIED_BOOT),
            mainlinePatchLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mainlinePatchLevel(pm) else null,
        ),
        keyguardManager = keyguard?.let { AndroidKeyguardManagerInfo(it.isDeviceSecure) },
        biometricManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(BiometricManager::class.java)?.let {
                AndroidBiometricManagerInfo(
                    deviceCredential = it.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS,
                    biometricStrong = it.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS,
                )
            }
        } else {
            null
        },
        devicePolicyManager = dpm?.let(::passwordComplexity)?.let { AndroidDevicePolicyManagerInfo(it) },
    )
}

@Suppress("DEPRECATION", "SwallowedException")
private fun mainlinePatchLevel(pm: PackageManager): String? = try {
    pm.getPackageInfo(MODULE_METADATA_PACKAGE, 0).versionName
} catch (e: PackageManager.NameNotFoundException) {
    null
}

private fun passwordComplexity(dpm: DevicePolicyManager): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        mapPasswordComplexity(dpm.passwordComplexity)
    } catch (e: SecurityException) {
        Log.w(e) { "REQUEST_PASSWORD_COMPLEXITY not granted; omitting passwordComplexity" }
        null
    }
}
