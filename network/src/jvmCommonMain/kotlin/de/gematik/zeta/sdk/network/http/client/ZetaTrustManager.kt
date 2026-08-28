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
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCertificateValidator
import de.gematik.zeta.sdk.network.http.client.config.tls.sanMatchesHost
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x509.GeneralName
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.milliseconds

@Suppress("CustomX509TrustManager")
internal class ZetaTrustManager(
    private val delegate: X509TrustManager,
    private val revocationChecker: RevocationChecker? = null,
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket,
    ) {
        delegateServerValidation(chain, authType, socket = socket)

        val session = (socket as? SSLSocket)?.handshakeSession
        validateSan(chain, session?.peerHost)

        val staple = session?.let(::extractStaple)

        validateZetaPolicy(chain)
        validateRevocation(chain, staple)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        engine: SSLEngine,
    ) {
        delegateServerValidation(chain = chain, authType = authType, engine = engine)

        val session = engine.handshakeSession
        validateSan(chain, session?.peerHost)

        val staple = session?.let(::extractStaple)

        validateZetaPolicy(chain)
        validateRevocation(chain, staple)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        delegate.checkServerTrusted(chain, authType)

        validateZetaPolicy(chain)
        validateRevocation(chain, staple = null)
    }

    private fun validateSan(chain: Array<out X509Certificate>, peerHost: String?) {
        val leaf = chain.firstOrNull()
            ?: throw CertificateException("Empty certificate chain")

        if (peerHost == null) {
            Log.e { "ZetaTls: no peer host available — rejecting" }
            throw CertificateException("No peer host available for SAN validation")
        }

        val sanEntries = leaf.subjectAlternativeNames
            ?.filter { it[0] == GeneralName.dNSName || it[0] == GeneralName.iPAddress }
            ?.map { it[1] as String }
            ?: emptyList()

        if (sanEntries.isEmpty()) {
            Log.e { "ZetaTls: certificate has no SAN entries for host=$peerHost - rejecting" }
            throw CertificateException("Certificate has no SAN entries")
        }

        val sanValid = sanEntries.any { san -> sanMatchesHost(san, peerHost) }
        if (!sanValid) {
            Log.e { "ZetaTls: SAN mismatch: host=$peerHost SANs=$sanEntries" }
            throw CertificateException("SAN mismatch: host=$peerHost SANs=$sanEntries")
        }

        Log.i { "ZetaTls: SAN validated: host=$peerHost matched in $sanEntries" }
    }

    private fun validateZetaPolicy(
        chain: Array<out X509Certificate>,
    ) {
        val result = ZetaCertificateValidator.validateChain(
            chain = chain.map { it.toZetaCertInfo() },
            nowEpochSeconds = System.currentTimeMillis() / 1_000,
        )

        if (!result.isValid) {
            throw CertificateException("gematik cert validation failed: ${result.errors}")
        }

        result.warnings.forEach { warning ->
            Log.w { "ZetaCert: $warning" }
        }
    }

    private fun validateRevocation(
        chain: Array<out X509Certificate>,
        staple: ByteArray?,
    ) {
        val checker = revocationChecker ?: return

        val revocationChain = resolveRevocationChain(chain)
        try {
            runBlocking {
                withTimeout(5_000.milliseconds) {
                    checker.validateChain(
                        chain = revocationChain,
                        stapledOcspResponse = staple,
                    )
                }
            }
        } catch (exception: Throwable) {
            throw CertificateException(
                "Revocation check failed during TLS handshake: " +
                    exception.message,
                exception,
            )
        }
    }

    private fun resolveRevocationChain(
        chain: Array<out X509Certificate>,
    ): List<ByteArray> {
        require(chain.isNotEmpty()) { "Certificate chain must not be empty" }

        val certificates = chain.toMutableList()
        val lastCertificate = certificates.last()

        if (!lastCertificate.isSelfSigned()) {
            val issuer = findIssuer(lastCertificate)

            if (issuer != null) {
                certificates += issuer

                Log.d {
                    "OCSP JVM: issuer resolved from trust store; " +
                        "chain extended from ${chain.size} to ${certificates.size}"
                }
            }
        }

        if (certificates.size < 2) {
            throw CertificateException(
                "Could not resolve issuer for revocation validation: " +
                    "chainSize=${chain.size}, " +
                    "certificateSubject=${lastCertificate.subjectX500Principal}, " +
                    "certificateIssuer=${lastCertificate.issuerX500Principal}",
            )
        }

        return certificates.map { certificate ->
            certificate.encoded
        }
    }

    internal fun findIssuer(
        cert: X509Certificate,
    ): X509Certificate? =
        delegate.acceptedIssuers.firstOrNull { candidate ->
            candidate.subjectX500Principal == cert.issuerX500Principal &&
                runCatching {
                    cert.verify(candidate.publicKey)
                }.isSuccess
        }

    internal fun X509Certificate.isSelfSigned(): Boolean =
        subjectX500Principal == issuerX500Principal &&
            runCatching {
                verify(publicKey)
            }.isSuccess

    private fun delegateServerValidation(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket? = null,
        engine: SSLEngine? = null,
    ) {
        val extended = delegate as? X509ExtendedTrustManager

        when {
            extended != null && socket != null ->
                extended.checkServerTrusted(chain, authType, socket)

            extended != null && engine != null ->
                extended.checkServerTrusted(chain, authType, engine)

            else ->
                delegate.checkServerTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket,
    ) {
        val extended = delegate as? X509ExtendedTrustManager
        if (extended != null) {
            extended.checkClientTrusted(chain, authType, socket)
        } else {
            delegate.checkClientTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        engine: SSLEngine,
    ) {
        val extended = delegate as? X509ExtendedTrustManager
        if (extended != null) {
            extended.checkClientTrusted(chain, authType, engine)
        } else {
            delegate.checkClientTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegate.acceptedIssuers
}
