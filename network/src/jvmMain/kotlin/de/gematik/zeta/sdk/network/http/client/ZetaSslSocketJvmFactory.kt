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

import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaSignatureAlgorithms
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsCurves
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal class ZetaSslSocketJvmFactory(
    delegate: SSLSocketFactory,
) : ZetaSslSocketSharedFactory(delegate) {

    /**
     * JVM exposes [SSLParameters.namedGroups] and [SSLParameters.signatureSchemes]
     * for explicit curve and signature algorithm control per gematik spec.
     */
    override fun applyPlatformParams(params: SSLParameters, socket: SSLSocket) {
        params.namedGroups = ZetaTlsCurves.ALLOWED.toTypedArray()
        params.signatureSchemes = ZetaSignatureAlgorithms.ALLOWED.toTypedArray()
    }
}
