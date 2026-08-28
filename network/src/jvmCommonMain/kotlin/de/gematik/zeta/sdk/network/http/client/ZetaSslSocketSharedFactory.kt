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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCipherSuites
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsProtocols
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsValidator
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal abstract class ZetaSslSocketSharedFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> =
        ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.toTypedArray()

    override fun getSupportedCipherSuites(): Array<String> =
        ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.toTypedArray()

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        (delegate.createSocket(s, host, port, autoClose) as SSLSocket).also(::configure)

    override fun createSocket(host: String, port: Int): Socket =
        (delegate.createSocket(host, port) as SSLSocket).also(::configure)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        (delegate.createSocket(host, port) as SSLSocket).also(::configure)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        (delegate.createSocket(host, port, localHost, localPort) as SSLSocket).also(::configure)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        (delegate.createSocket(address, port, localAddress, localPort) as SSLSocket).also(::configure)

    /**
     * Apply platform-specific [SSLParameters] that are not universally supported.
     * Called after the common parameters (protocols, cipher suites) have been set,
     * and before [SSLParameters] is written back to the socket.
     */
    protected abstract fun applyPlatformParams(params: SSLParameters, socket: SSLSocket)

    private fun configure(socket: SSLSocket): SSLSocket {
        val params = socket.sslParameters
        params.protocols = ZetaTlsProtocols.ALLOWED
            .filter { it in socket.supportedProtocols }
            .toTypedArray()

        params.cipherSuites = ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA
            .filter { it in socket.supportedCipherSuites }
            .toTypedArray()

        applyPlatformParams(params, socket)

        socket.sslParameters = params

        Log.i {
            "ZetaTls: socket configured: " +
                "protocols=${params.protocols.toList()}, " +
                "cipherSuites=${params.cipherSuites.toList()}"
        }

        val result = ZetaTlsValidator.validateEnabledCipherSuites(socket.enabledCipherSuites.toList())
        if (!result.isCompliant) {
            Log.e { "ZetaTls: cipher suite validation FAILED: ${result.errors}" }
            throw SSLException("gematik TLS cipher suite compliance failure: ${result.errors}")
        }
        result.warnings.forEach { Log.w { "ZetaTls: $it" } }

        return socket
    }
}
