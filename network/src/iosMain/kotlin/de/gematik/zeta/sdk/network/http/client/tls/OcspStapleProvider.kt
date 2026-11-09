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

package de.gematik.zeta.sdk.network.http.client.tls

import de.gematik.zeta.logging.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreFoundation.CFRelease
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_protocol_metadata
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_protocol_copy_tls_definition
import platform.Network.nw_protocol_metadata_is_tls
import platform.Network.nw_tls_copy_sec_protocol_metadata
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.sec_certificate_copy_ref
import platform.Security.sec_protocol_metadata_access_ocsp_response
import platform.Security.sec_protocol_metadata_access_peer_certificate_chain
import platform.Security.sec_protocol_options_set_tls_ocsp_enabled
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_TIMEOUT_MS = 3_000L

internal interface OcspStapleProvider {
    suspend fun getStaple(host: String, port: Int, expectedLeaf: ByteArray): ByteArray?
}

internal class NetworkOcspStapleProvider(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : OcspStapleProvider {

    override suspend fun getStaple(
        host: String,
        port: Int,
        expectedLeaf: ByteArray,
    ): ByteArray? {
        val result = probe(host = host, port = port) ?: return null

        if (!result.leaf.contentEquals(expectedLeaf)) {
            Log.w { "[TLS-PROBE] leaf mismatch host=$host:$port, ignoring probed staple" }
            return null
        }

        return result.staple
    }

    private suspend fun probe(host: String, port: Int): StapleProbeResult? =
        withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val parameters = nw_parameters_create_secure_tcp(
                    { tlsOptions ->
                        val securityOptions = nw_tls_copy_sec_protocol_options(tlsOptions)
                        sec_protocol_options_set_tls_ocsp_enabled(securityOptions, true)
                    },
                    {
                        // Default TCP configuration.
                    },
                )

                val endpoint = nw_endpoint_create_host(host, port.toString())
                val connection = nw_connection_create(endpoint, parameters)
                val queue = dispatch_queue_create("de.gematik.zeta.ocsp-probe", null)

                nw_connection_set_queue(connection, queue)

                // State callbacks execute on the serial queue above.
                var finished = false

                fun finish(result: StapleProbeResult?) {
                    if (finished) {
                        return
                    }

                    finished = true
                    nw_connection_cancel(connection)

                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                nw_connection_set_state_changed_handler(connection) { state, error ->
                    when (state) {
                        nw_connection_state_ready -> { finish(readTlsMetadata(connection = connection, host = host)) }

                        nw_connection_state_failed -> {
                            Log.w { "[TLS-PROBE] connection failed host=$host error=$error" }
                            finish(null)
                        }

                        nw_connection_state_cancelled -> { finish(null) }

                        else -> Unit
                    }
                }

                continuation.invokeOnCancellation { nw_connection_cancel(connection) }
                nw_connection_start(connection)
            }
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun readTlsMetadata(
        connection: nw_connection_t,
        host: String,
    ): StapleProbeResult? {
        val protocolMetadata = nw_connection_copy_protocol_metadata(connection, nw_protocol_copy_tls_definition())

        if (protocolMetadata == null || !nw_protocol_metadata_is_tls(protocolMetadata)) {
            Log.w { "[TLS-PROBE] no TLS metadata host=$host" }
            return null
        }

        val securityMetadata = nw_tls_copy_sec_protocol_metadata(protocolMetadata)
        var staple: ByteArray? = null

        sec_protocol_metadata_access_ocsp_response(securityMetadata) { data ->
            if (data != null) {
                staple = DarwinSecurityUtils.dispatchDataToByteArray(data)
            }
        }

        var leaf: ByteArray? = null
        sec_protocol_metadata_access_peer_certificate_chain(
            securityMetadata,
        ) { certificate ->
            if (leaf == null && certificate != null) {
                val certificateRef = sec_certificate_copy_ref(certificate)

                if (certificateRef != null) {
                    try {
                        leaf = DarwinSecurityUtils.certificateToDer(certificateRef)
                    } finally {
                        CFRelease(certificateRef)
                    }
                }
            }
        }

        val leafDer = leaf ?: run {
            Log.w { "[TLS-PROBE] no peer certificate host=$host" }
            return null
        }

        Log.d { "[TLS-PROBE] host=$host leafBytes=${leafDer.size} stapleBytes=${staple?.size ?: 0}" }

        return StapleProbeResult(leaf = leafDer, staple = staple)
    }

    private data class StapleProbeResult(
        val leaf: ByteArray,
        val staple: ByteArray?,
    )
}
