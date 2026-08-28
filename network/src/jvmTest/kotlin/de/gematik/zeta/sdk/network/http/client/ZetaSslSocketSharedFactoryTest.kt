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
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaSignatureAlgorithms
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsCurves
import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsProtocols
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.BeforeClass
import java.net.InetAddress
import java.net.Socket
import java.security.Security
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ZetaSslSocketFactoryTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setupBouncyCastle() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private val validEnabledSuites: Array<String> =
        (ZetaCipherSuites.REQUIRED_TLS_1_2 + ZetaCipherSuites.TLS_1_3_SUITES).toTypedArray()

    private fun buildFactory(
        delegate: SSLSocketFactory = mockk(),
    ): ZetaSslSocketSharedFactory =
        ZetaSslSocketJvmFactory(delegate = delegate)

    private fun buildMockSocket(
        params: SSLParameters = SSLParameters(),
        supportedProtocols: Array<String> = ZetaTlsProtocols.ALLOWED.toTypedArray(),
        supportedCipherSuites: Array<String> = ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.toTypedArray(),
        enabledCipherSuites: Array<String> = validEnabledSuites,
    ): SSLSocket = mockk<SSLSocket>(relaxed = true).also { socket ->
        every { socket.sslParameters } returns params
        every { socket.sslParameters = any() } just Runs
        every { socket.supportedProtocols } returns supportedProtocols
        every { socket.supportedCipherSuites } returns supportedCipherSuites
        every { socket.enabledCipherSuites } returns enabledCipherSuites
    }

    private fun buildMockDelegate(socket: SSLSocket): SSLSocketFactory =
        mockk<SSLSocketFactory>().also { d ->
            every { d.createSocket(any<Socket>(), any<String>(), any<Int>(), any<Boolean>()) } returns socket
            every { d.createSocket(any<String>(), any<Int>()) } returns socket
            every { d.createSocket(any<InetAddress>(), any<Int>()) } returns socket
            every { d.createSocket(any<String>(), any<Int>(), any<InetAddress>(), any<Int>()) } returns socket
            every { d.createSocket(any<InetAddress>(), any<Int>(), any<InetAddress>(), any<Int>()) } returns socket
        }

    @Test
    fun getDefaultCipherSuites_returnsFullIanaList() {
        assertContentEquals(
            ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.toTypedArray(),
            buildFactory().getDefaultCipherSuites(),
        )
    }

    @Test
    fun getDefaultCipherSuites_containsAllRequiredTls12Suites() {
        val result = buildFactory().getDefaultCipherSuites().toSet()
        val tls13 = ZetaCipherSuites.TLS_1_3_SUITES.toSet()
        val requiredTls12Iana = ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.filterNot { it in tls13 }

        requiredTls12Iana.forEach { assertTrue(it in result, "Missing: $it") }
    }

    @Test
    fun getDefaultCipherSuites_containsAllTls13Suites() {
        val result = buildFactory().getDefaultCipherSuites().toSet()
        ZetaCipherSuites.TLS_1_3_SUITES.forEach { assertTrue(it in result, "Missing: $it") }
    }

    @Test
    fun getDefaultCipherSuites_sizeMatchesFullPreferredOrder() {
        assertEquals(ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.size, buildFactory().getDefaultCipherSuites().size)
    }

    @Test
    fun getSupportedCipherSuites_returnsFullIanaList() {
        assertContentEquals(
            ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.toTypedArray(),
            buildFactory().getSupportedCipherSuites(),
        )
    }

    @Test
    fun getSupportedCipherSuites_matchesGetDefaultCipherSuites() {
        val factory = buildFactory()
        assertContentEquals(factory.getDefaultCipherSuites(), factory.getSupportedCipherSuites())
    }

    @Test
    fun createSocket_socketHostPortAutoClose_returnsConfiguredSocket() {
        val mockSocket = buildMockSocket()
        val result = buildFactory(buildMockDelegate(mockSocket))
            .createSocket(mockk<Socket>(relaxed = true), "host", 443, true)
        assertNotNull(result)
    }

    @Test
    fun createSocket_hostPort_returnsConfiguredSocket() {
        val mockSocket = buildMockSocket()
        val result = buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertNotNull(result)
    }

    @Test
    fun createSocket_inetAddressPort_returnsConfiguredSocket() {
        val mockSocket = buildMockSocket()
        val result = buildFactory(buildMockDelegate(mockSocket))
            .createSocket(InetAddress.getLoopbackAddress(), 443)
        assertNotNull(result)
    }

    @Test
    fun createSocket_hostPortLocalHostLocalPort_returnsConfiguredSocket() {
        val mockSocket = buildMockSocket()
        val result = buildFactory(buildMockDelegate(mockSocket))
            .createSocket("host", 443, InetAddress.getLoopbackAddress(), 0)
        assertNotNull(result)
    }

    @Test
    fun createSocket_addressPortLocalAddressLocalPort_returnsConfiguredSocket() {
        val mockSocket = buildMockSocket()
        val result = buildFactory(buildMockDelegate(mockSocket))
            .createSocket(InetAddress.getLoopbackAddress(), 443, InetAddress.getLoopbackAddress(), 0)
        assertNotNull(result)
    }

    @Test
    fun configure_setsAllowedProtocols_filteredBySupportedProtocols() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(
            params = params,
            supportedProtocols = arrayOf("TLSv1.2", "TLSv1.1", "SSLv3"),
        )
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertTrue("TLSv1.2" in params.protocols)
        assertFalse("TLSv1.1" in params.protocols)
        assertFalse("SSLv3" in params.protocols)
    }

    @Test
    fun configure_setsBothAllowedProtocols_whenBothSupported() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(
            params = params,
            supportedProtocols = arrayOf("TLSv1.2", "TLSv1.3"),
        )
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertTrue("TLSv1.2" in params.protocols)
        assertTrue("TLSv1.3" in params.protocols)
    }

    @Test
    fun configure_setsEmptyProtocols_whenNoSupportedProtocolsAllowed() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(
            params = params,
            supportedProtocols = arrayOf("SSLv2", "SSLv3"),
        )
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertTrue(params.protocols.isEmpty())
    }

    @Test
    fun configure_excludesForbiddenProtocols_evenIfSupported() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(
            params = params,
            supportedProtocols = arrayOf("TLSv1.2", "TLSv1", "TLSv1.1"),
        )
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertFalse("TLSv1" in params.protocols)
        assertFalse("TLSv1.1" in params.protocols)
    }

    @Test
    fun configure_setsOnlyAllowedCipherSuites_filteredBySupportedSuites() {
        val params = SSLParameters()
        val ianaName = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"

        val mockSocket = buildMockSocket(
            params = params,
            supportedCipherSuites = arrayOf(ianaName, "TLS_UNSUPPORTED_CIPHER"),
        )
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertTrue(ianaName in params.cipherSuites)
        assertFalse("TLS_UNSUPPORTED_CIPHER" in params.cipherSuites)
    }

    @Test
    fun configure_setsEmptyCipherSuites_whenNoneSupported() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(params = params, supportedCipherSuites = arrayOf("TLS_NONE"))
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertTrue(params.cipherSuites.isEmpty())
    }

    @Test
    fun configure_setsAllSuites_whenAllAreSupported() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(
            params = params,
            supportedCipherSuites = ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.toTypedArray(),
        )
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertEquals(ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA.size, params.cipherSuites.size)
    }

    @Test
    fun configure_setsAllAllowedNamedGroups_onSslParameters() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(params = params)
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertContentEquals(ZetaTlsCurves.ALLOWED.toTypedArray(), params.namedGroups)
    }

    @Test
    fun configure_namedGroupsContainP256() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(params = params)
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertTrue(ZetaTlsCurves.P256 in requireNotNull(params.namedGroups).toList())
    }

    @Test
    fun configure_setsAllowedSignatureSchemes_always() {
        val params = SSLParameters()
        val mockSocket = buildMockSocket(params = params)
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)

        assertContentEquals(ZetaSignatureAlgorithms.ALLOWED.toTypedArray(), params.signatureSchemes)
    }

    @Test
    fun configure_overridesExistingSignatureSchemes_withAllowedList() {
        val params = SSLParameters()
        params.signatureSchemes = arrayOf("rsa_pss_rsae_sha256")
        val mockSocket = buildMockSocket(params = params)
        buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)

        assertContentEquals(ZetaSignatureAlgorithms.ALLOWED.toTypedArray(), params.signatureSchemes)
    }

    @Test
    fun configure_throwsSSLException_whenEnabledCipherSuitesIsEmpty() {
        val mockSocket = buildMockSocket(enabledCipherSuites = emptyArray())

        assertFailsWith<SSLException> {
            buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        }
    }

    @Test
    fun configure_throwsSSLException_whenRequiredTls12SuitesMissing() {
        val mockSocket = buildMockSocket(
            enabledCipherSuites = arrayOf(ZetaCipherSuites.AES_128_GCM_SHA256),
        )
        assertFailsWith<SSLException> {
            buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        }
    }

    @Test
    fun configure_sslExceptionMessage_containsComplianceText() {
        val mockSocket = buildMockSocket(enabledCipherSuites = emptyArray())
        val ex = assertFailsWith<SSLException> {
            buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        }
        assertTrue(
            ex.message?.contains("gematik") == true || ex.message?.contains("compliance") == true,
            "SSLException message should reference compliance: ${ex.message}",
        )
    }

    @Test
    fun configure_doesNotThrow_whenAllRequiredSuitesPresent() {
        val mockSocket = buildMockSocket(enabledCipherSuites = validEnabledSuites)
        val result = buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)
        assertNotNull(result)
    }

    @Test
    fun configure_throwsSSLException_forAllCreateSocketOverloads_whenValidationFails() {
        fun factory() = buildFactory(buildMockDelegate(buildMockSocket(enabledCipherSuites = emptyArray())))

        assertFailsWith<SSLException> { factory().createSocket(mockk<Socket>(relaxed = true), "h", 443, true) }
        assertFailsWith<SSLException> { factory().createSocket("host", 443) }
        assertFailsWith<SSLException> { factory().createSocket(InetAddress.getLoopbackAddress(), 443) }
        assertFailsWith<SSLException> { factory().createSocket("host", 443, InetAddress.getLoopbackAddress(), 0) }
        assertFailsWith<SSLException> {
            factory().createSocket(InetAddress.getLoopbackAddress(), 443, InetAddress.getLoopbackAddress(), 0)
        }
    }

    @Test
    fun configure_returnsTheSameSocketInstance_thatWasPassedIn() {
        val mockSocket = buildMockSocket()
        val result = buildFactory(buildMockDelegate(mockSocket)).createSocket("host", 443)

        assertSame(mockSocket, result)
    }
}
