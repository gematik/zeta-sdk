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

package de.gematik.zeta.sdk.network.http.client.config.tls

import de.gematik.zeta.logging.Log
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

@Suppress("CustomX509TrustManager")
internal class ZetaTrustManager(
    private val delegate: X509TrustManager,
) : X509TrustManager by delegate {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val result = ZetaCertificateValidator.validateChain(
            chain = chain.map { it.toZetaCertInfo() },
            nowEpochSeconds = System.currentTimeMillis() / 1000,
        )

        if (!result.isValid) {
            throw SSLException("gematik cert validation failed: ${result.errors}")
        }

        result.warnings.forEach { Log.w { "ZetaCert: $it" } }

        delegate.checkServerTrusted(chain, authType)
    }
}
