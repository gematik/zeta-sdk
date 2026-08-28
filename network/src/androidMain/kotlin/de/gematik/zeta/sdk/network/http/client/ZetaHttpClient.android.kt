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

package de.gematik.zeta.sdk.network.http.client

import android.os.Build
import de.gematik.zeta.android.SdkAndroidContext
import de.gematik.zeta.logging.Log
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory

internal actual fun loadCaFromFile(
    path: String,
    certFactory: CertificateFactory,
): List<X509Certificate> =
    SdkAndroidContext.get().assets.open(File(path).name).use { input ->
        certFactory.generateCertificates(input).map { it as X509Certificate }
    }

internal actual fun createPlatformSslSocketFactory(base: SSLSocketFactory): SSLSocketFactory =
    ZetaSslSocketAndroidFactory(base)

/**
 * OCSP stapling extraction on Android.
 * ExtendedSSLSession.getStatusResponses() requires API level 37+.
 * On older devices, this returns null.
 */
internal actual fun extractStaple(session: SSLSession): ByteArray? {
    if (Build.VERSION.SDK_INT < 37) {
        Log.i { "ZetaTls: staple response cannot be extracted for Android SDK prior to 37" }
        return null
    }

    val extendedSession = session as? ExtendedSSLSession ?: return null
    val statusResponses = extendedSession.statusResponses
    return statusResponses?.firstOrNull()
}
