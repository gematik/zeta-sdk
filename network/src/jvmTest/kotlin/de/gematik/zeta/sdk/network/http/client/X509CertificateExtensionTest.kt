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

import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertTrue
import java.math.BigInteger
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class X509CertificateExtensionTest {

    private fun mockEcCert(bitLength: Int): X509Certificate {
        val ecKey = mockk<ECPublicKey>()
        val params = mockk<ECParameterSpec>()
        every { params.order } returns BigInteger.ONE.shiftLeft(bitLength - 1)
        every { ecKey.params } returns params
        every { ecKey.algorithm } returns "EC"

        val cert = mockk<X509Certificate>()
        every { cert.publicKey } returns ecKey
        every { cert.subjectX500Principal } returns X500Principal("CN=test")
        every { cert.sigAlgName } returns "SHA256withECDSA"
        every { cert.notBefore } returns Date(1000000)
        every { cert.notAfter } returns Date(9000000)
        every { cert.subjectAlternativeNames } returns listOf(listOf(2, "test.example.com"))
        return cert
    }

    private fun mockRsaCert(): X509Certificate {
        val rsaKey = mockk<RSAPublicKey>()
        every { rsaKey.modulus } returns BigInteger.ONE.shiftLeft(2048 - 1)
        every { rsaKey.algorithm } returns "RSA"

        val cert = mockk<X509Certificate>()
        every { cert.publicKey } returns rsaKey
        every { cert.subjectX500Principal } returns X500Principal("CN=test")
        every { cert.sigAlgName } returns "SHA256withRSA"
        every { cert.notBefore } returns Date(1000000)
        every { cert.notAfter } returns Date(9000000)
        every { cert.subjectAlternativeNames } returns listOf(listOf(2, "test.example.com"))
        return cert
    }

    private fun mockUnknownKeyCert(): X509Certificate {
        val unknownKey = mockk<PublicKey>()
        every { unknownKey.algorithm } returns "DSA"

        val cert = mockk<X509Certificate>()
        every { cert.publicKey } returns unknownKey
        every { cert.subjectX500Principal } returns X500Principal("CN=test")
        every { cert.sigAlgName } returns "SHA256withDSA"
        every { cert.notBefore } returns Date(1000000)
        every { cert.notAfter } returns Date(9000000)
        every { cert.subjectAlternativeNames } returns listOf(listOf(2, "test.example.com"))
        return cert
    }

    @Test
    fun toZetaCertInfo_ecP256_returnsSecp256r1() {
        // Arrange
        val cert = mockEcCert(256)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertEquals("secp256r1", info.curveName)
        assertEquals(256, info.keySize)
        assertEquals("EC", info.keyAlgorithm)
    }

    @Test
    fun toZetaCertInfo_ecP384_returnsSecp384r1() {
        // Arrange
        val cert = mockEcCert(384)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertEquals("secp384r1", info.curveName)
        assertEquals(384, info.keySize)
    }

    @Test
    fun toZetaCertInfo_ecP521_returnsSecp521r1() {
        // Arrange
        val cert = mockEcCert(521)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertEquals("secp521r1", info.curveName)
        assertEquals(521, info.keySize)
    }

    @Test
    fun toZetaCertInfo_ecUnknownSize_returnsNullCurve() {
        // Arrange
        val cert = mockEcCert(224)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertNull(info.curveName)
        assertEquals(224, info.keySize)
    }

    @Test
    fun toZetaCertInfo_rsa_returnsNullCurve() {
        // Arrange
        val cert = mockRsaCert()

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertNull(info.curveName)
        assertEquals(2048, info.keySize)
        assertEquals("RSA", info.keyAlgorithm)
    }

    @Test
    fun toZetaCertInfo_unknownKeyType_returnsZeroSizeAndNullCurve() {
        // Arrange
        val cert = mockUnknownKeyCert()

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertNull(info.curveName)
        assertEquals(0, info.keySize)
    }

    @Test
    fun toZetaCertInfo_notBefore_isConvertedToSeconds() {
        // Arrange
        val cert = mockEcCert(256)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertEquals(1000L, info.notBefore)
    }

    @Test
    fun toZetaCertInfo_notAfter_isConvertedToSeconds() {
        // Arrange
        val cert = mockEcCert(256)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertEquals(9000L, info.notAfter)
    }

    @Test
    fun toZetaCertInfo_subjectDN_isPopulated() {
        // Arrange
        val cert = mockEcCert(256)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertTrue(info.subjectDN.isNotBlank())
    }

    @Test
    fun toZetaCertInfo_sigAlgName_isPopulated() {
        // Arrange
        val cert = mockEcCert(256)

        // Act
        val info = cert.toZetaCertInfo()

        // Assert
        assertEquals("SHA256withECDSA", info.sigAlgName)
    }
}
