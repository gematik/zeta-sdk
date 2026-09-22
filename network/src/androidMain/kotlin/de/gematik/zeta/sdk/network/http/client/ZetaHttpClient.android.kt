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
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsProtocols.TLS_1_2
import org.conscrypt.Conscrypt
import org.conscrypt.ZetaConscryptStaple
import java.io.File
import java.security.Provider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
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
 * Conscrypt provider backing the SDK's TLS stack.
 *
 * Bundled so the SDK can read the server's stapled OCSP response.
 * Android exposes staples only via `ExtendedSSLSession.getStatusResponses()` (API 37+), while
 * minSdk is 28; building the SDK's [SSLContext] from Conscrypt makes every session a
 * `ConscryptSession`, whose `statusResponses` is readable on all supported levels
 */
private val conscryptProvider: Provider by lazy {
    if (!Conscrypt.isAvailable()) {
        val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        throw SSLException(
            "gematik TLS compliance failure: Conscrypt is unavailable, OCSP stapling cannot be " +
                "verified (abis=$abis)",
        )
    }
    Conscrypt.newProvider().also { provider ->
        val version = Conscrypt.version()
        val versionText = if (version == null) {
            "unknown"
        } else {
            "${version.major()}.${version.minor()}.${version.patch()}"
        }
        Log.i { "ZetaTls: TLS stack provider=${provider.name}, Conscrypt=$versionText" }
    }
}

internal actual fun createPlatformSslContext(): SSLContext =
    createPlatformSslContext(onAndroidRuntime = Build.SUPPORTED_ABIS != null)

/**
 * @param onAndroidRuntime `false` only under host-JVM unit tests, where [Build.SUPPORTED_ABIS] is null;
 * Device always reports its ABIs.
 */
internal fun createPlatformSslContext(onAndroidRuntime: Boolean): SSLContext {
    if (!onAndroidRuntime) {
        Log.w { "ZetaTls: not an Android runtime, using the platform TLS provider (no OCSP staple)" }
        return SSLContext.getInstance(TLS_1_2)
    }
    return SSLContext.getInstance(TLS_1_2, conscryptProvider)
}

internal actual fun extractStaple(session: SSLSession): ByteArray? {
    val statusResponses = ZetaConscryptStaple.statusResponses(session)
    if (statusResponses == null) {
        Log.w { "ZetaTls: session is not a ConscryptSession (${session.javaClass.name})" }
        return null
    }
    return statusResponses.firstOrNull()
}
