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

package de.gematik.zeta.client.config

import android.content.pm.PackageManager
import android.security.keystore.KeyProperties
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import java.security.MessageDigest

internal actual fun platformProductId(clientName: String): PlatformProductId {
    val context = AndroidConfig.context()
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signers = info.signingInfo?.apkContentsSigners.orEmpty()
    return PlatformProductId.AndroidProductId(
        packageName = context.packageName,
        sha256CertFingerprints = signers.map { sha256Fingerprint(it.toByteArray()) },
    )
}

private fun sha256Fingerprint(certificate: ByteArray): String =
    MessageDigest.getInstance(KeyProperties.DIGEST_SHA256).digest(certificate).joinToString(":") { "%02X".format(it) }
