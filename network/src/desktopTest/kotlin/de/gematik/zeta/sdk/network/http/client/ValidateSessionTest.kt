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

import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaCipherSuites
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsProtocols
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.engine.curl.tls.ChainCertMeta
import io.ktor.client.engine.curl.tls.LeafCertInfo
import io.ktor.client.engine.curl.tls.TlsSessionData
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ValidateSessionTest {

    private val clock = ZetaClock { Instant.fromEpochSeconds(1_000) }

    private fun leafChainMeta(san: List<String>, commonName: String?) = ChainCertMeta(
        keyTypeName = "EC",
        keyBits = 256,
        sigAlgSn = "ecdsa-with-SHA256",
        subjectDN = "/CN=$commonName",
        notBefore = 0,
        notAfter = 2_000,
        curveName = "secp256r1",
        san = san,
        commonName = commonName,
    )

    private fun session(host: String, san: List<String>, commonName: String?, withChain: Boolean): TlsSessionData {
        val meta = leafChainMeta(san, commonName)
        return TlsSessionData(
            protocol = ZetaTlsProtocols.TLS_1_3,
            cipherSuite = ZetaCipherSuites.TLS_1_3_SUITES.first(),
            host = host,
            leafCertInfo = LeafCertInfo(
                keyTypeName = meta.keyTypeName,
                keyBits = meta.keyBits,
                sigAlgSn = meta.sigAlgSn,
                subjectDN = meta.subjectDN,
                notBefore = meta.notBefore,
                notAfter = meta.notAfter,
                curveName = meta.curveName,
                san = san,
                commonName = commonName,
                fullChain = if (withChain) listOf(meta) else null,
            ),
        )
    }

    @Test
    fun acceptsHostInCommonNameWhenSanIsMissingWithChain() {
        validateSession(session("zeta.example.com", emptyList(), "zeta.example.com", withChain = true), clock)
    }

    @Test
    fun acceptsHostInCommonNameWhenSanIsMissingLeafOnly() {
        validateSession(session("zeta.example.com", emptyList(), "zeta.example.com", withChain = false), clock)
    }

    @Test
    fun rejectsCommonNameMismatchWhenSanIsMissing() {
        assertFailsWith<IllegalStateException> {
            validateSession(session("zeta.example.com", emptyList(), "other.example.com", withChain = true), clock)
        }
    }
}
