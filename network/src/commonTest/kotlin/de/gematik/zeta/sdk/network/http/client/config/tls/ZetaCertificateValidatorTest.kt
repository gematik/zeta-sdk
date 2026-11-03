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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZetaCertificateValidatorTest {

    private val now = 1000L

    private fun validEcCert() = ZetaCertInfo(
        subjectDN = "CN=test",
        sigAlgName = "SHA256WITHECDSA",
        keyAlgorithm = "EC",
        keySize = 256,
        curveName = "secp256r1",
        notBefore = 0,
        notAfter = 2000,
    )

    private fun rsaCert(keySize: Int, sigAlgName: String = "SHA256WITHRSA", subjectDN: String = "CN=rsa-chain") =
        ZetaCertInfo(
            subjectDN = subjectDN,
            sigAlgName = sigAlgName,
            keyAlgorithm = "RSA",
            keySize = keySize,
            notBefore = 0,
            notAfter = 2000,
        )

    @Test
    fun validate_withValidEcCert_returnsIsValidTrue() {
        // Arrange
        val cert = validEcCert()

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validate_withForbiddenSignatureAlgorithm_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(sigAlgName = "SHA1WITHECDSA")

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "Forbidden signature algorithm" in it })
    }

    @Test
    fun validate_withUnknownSignatureAlgorithm_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(sigAlgName = "UNKNOWN_ALG")

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "not allowed" in it })
    }

    @Test
    fun validate_withRsaCert_returnsIsValidFalse() {
        // Arrange
        val cert = ZetaCertInfo(
            subjectDN = "CN=rsa-test",
            sigAlgName = "SHA256WITHRSA",
            keyAlgorithm = "RSA",
            keySize = 2048,
            notBefore = 0,
            notAfter = 2000,
        )

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
    }

    @Test
    fun validate_withEcKeyTooSmall_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(keySize = 128)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "EC key too small" in it })
    }

    @Test
    fun validate_withForbiddenEcCurve_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(curveName = "prime192v1")

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "EC curve" in it && "not allowed" in it })
    }

    @Test
    fun validate_withNullCurveName_addsCurveError() {
        // Arrange
        val cert = validEcCert().copy(curveName = null)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "EC certificate has no named curve" in it })
    }

    @Test
    fun validate_withCertNotYetValid_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(notBefore = 2000)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "not yet valid" in it })
    }

    @Test
    fun validate_withExpiredCert_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(notAfter = 500)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "expired" in it })
    }

    @Test
    fun validate_withMd5Rsa_returnsIsValidFalse() {
        // Arrange
        val cert = ZetaCertInfo(
            subjectDN = "CN=md5",
            sigAlgName = "MD5WITHRSA",
            keyAlgorithm = "RSA",
            keySize = 2048,
            notBefore = 0,
            notAfter = 2000,
        )

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "Forbidden signature algorithm" in it })
    }

    @Test
    fun validateChain_withAllValidCerts_returnsIsValidTrue() {
        // Arrange
        val chain = listOf(
            validEcCert(),
            validEcCert().copy(subjectDN = "CN=intermediate"),
        )

        // Act
        val result = ZetaCertificateValidator.validateChain(chain, now)

        // Assert
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validateChain_withOneInvalidCert_returnsIsValidFalse() {
        // Arrange
        val chain = listOf(
            validEcCert(),
            validEcCert().copy(sigAlgName = "SHA1WITHECDSA"),
        )

        // Act
        val result = ZetaCertificateValidator.validateChain(chain, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun validateChain_withEmptyChain_returnsIsValidTrue() {
        // Arrange
        val chain = emptyList<ZetaCertInfo>()

        // Act
        val result = ZetaCertificateValidator.validateChain(chain, now)

        // Assert
        assertTrue(result.isValid)
    }

    @Test
    fun ZetaCertificateAlgorithms_ALLOWED_SIGNATURE_ALGORITHMS_EE_containsExpectedAlgorithms() {
        // Arrange & Act
        val allowedEe = ZetaCertificateValidator.ZetaCertificateAlgorithms.ALLOWED_SIGNATURE_ALGORITHMS_EE

        // Assert
        assertEquals(2, allowedEe.size)
        assertTrue("SHA256WITHECDSA" in allowedEe)
        assertTrue("SHA384WITHECDSA" in allowedEe)
    }

    @Test
    fun ZetaCertificateAlgorithms_ALLOWED_SIGNATURE_ALGORITHMS_CHAIN_containsExpectedAlgorithms() {
        // Arrange & Act
        val allowedChain = ZetaCertificateValidator.ZetaCertificateAlgorithms.ALLOWED_SIGNATURE_ALGORITHMS_CHAIN

        // Assert
        assertEquals(6, allowedChain.size)
        assertTrue("SHA256WITHECDSA" in allowedChain)
        assertTrue("SHA384WITHECDSA" in allowedChain)
        assertTrue("SHA512WITHECDSA" in allowedChain)
        assertTrue("SHA256WITHRSA" in allowedChain)
        assertTrue("SHA384WITHRSA" in allowedChain)
        assertTrue("SHA512WITHRSA" in allowedChain)
    }

    @Test
    fun ZetaCertificateAlgorithms_FORBIDDEN_SIGNATURE_ALGORITHMS_containsExpectedAlgorithms_sizeIsFour() {
        // Arrange & Act
        val forbidden = ZetaCertificateValidator.ZetaCertificateAlgorithms.FORBIDDEN_SIGNATURE_ALGORITHMS

        // Assert
        assertEquals(4, forbidden.size)
        assertTrue("SHA1WITHECDSA" in forbidden)
        assertTrue("SHA1WITHRSA" in forbidden)
        assertTrue("MD5WITHRSA" in forbidden)
        assertTrue("MD2WITHRSA" in forbidden)
    }

    @Test
    fun ZetaCertificateAlgorithms_MIN_EC_KEY_BITS_value_is256() {
        // Arrange & Act & Assert
        assertEquals(256, ZetaCertificateValidator.ZetaCertificateAlgorithms.MIN_EC_KEY_BITS)
    }

    @Test
    fun validate_withRsaChainKeyTooSmall_returnsIsValidFalse() {
        // Arrange
        val cert = rsaCert(keySize = ZetaCertificateValidator.ZetaCertificateAlgorithms.MIN_RSA_KEY_BITS - 1)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, isLeaf = false)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "RSA key too small" in it })
        assertTrue(
            result.errors.any {
                it.contains("${ZetaCertificateValidator.ZetaCertificateAlgorithms.MIN_RSA_KEY_BITS - 1}") &&
                    it.contains("${ZetaCertificateValidator.ZetaCertificateAlgorithms.MIN_RSA_KEY_BITS}") &&
                    it.contains(cert.subjectDN)
            },
        )
    }

    @Test
    fun validate_withRsaChainKeyAtMinimum_returnsIsValidTrue() {
        // Arrange
        val cert = rsaCert(keySize = ZetaCertificateValidator.ZetaCertificateAlgorithms.MIN_RSA_KEY_BITS)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, isLeaf = false)

        // Assert
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun validate_withRsaChainKeyAboveMinimum_returnsIsValidTrue() {
        // Arrange
        val cert = rsaCert(keySize = ZetaCertificateValidator.ZetaCertificateAlgorithms.MIN_RSA_KEY_BITS + 1024)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, isLeaf = false)

        // Assert
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun validate_withRsaLeafKeyTooSmall_rejectedForKeyAlgorithmNotRsaKeySize() {
        val cert = rsaCert(keySize = 512)

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "not allowed for leaf cert" in it })
        assertFalse(result.errors.any { "RSA key too small" in it })
    }

    @Test
    fun validate_withHostNull_skipsSanValidation() {
        // Arrange
        val cert = validEcCert().copy(san = emptyList())

        // Act
        val result = ZetaCertificateValidator.validate(cert, now)

        // Assert
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun validate_withHostMatchingExactSan_returnsIsValidTrue() {
        // Arrange
        val cert = validEcCert().copy(san = listOf("example.com"))

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "example.com")

        // Assert
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun validate_withHostNotInSan_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(san = listOf("example.com"))

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "not-example.com")

        // Assert
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any {
                "Certificate SAN does not match host" in it && "not-example.com" in it
            },
        )
    }

    @Test
    fun validate_withEmptySanList_andHostProvided_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(san = emptyList())

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "example.com")

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "Certificate SAN does not match host" in it })
    }

    @Test
    fun validate_withMultipleSanEntries_matchesAnyOfThem() {
        // Arrange
        val cert = validEcCert().copy(san = listOf("other.example.com", "example.com", "another.example.com"))

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "example.com")

        // Assert
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun validate_withNoSanAndHostInCommonName_returnsIsValidTrue() {
        // Arrange
        val cert = validEcCert().copy(san = emptyList(), commonName = "example.com")

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "example.com")

        // Assert
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun validate_withNoSanAndWildcardCommonName_matchesSubdomain() {
        // Arrange
        val cert = validEcCert().copy(san = emptyList(), commonName = "*.example.com")

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "api.example.com")

        // Assert
        assertTrue(result.isValid, "expected valid, got errors: ${result.errors}")
    }

    @Test
    fun validate_withNoSanAndCommonNameMismatch_returnsIsValidFalse() {
        // Arrange
        val cert = validEcCert().copy(san = emptyList(), commonName = "other.com")

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "example.com")

        // Assert
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "Certificate SAN does not match host" in it && "other.com" in it })
    }

    @Test
    fun validate_withSanMismatchAndHostInCommonName_ignoresCommonName() {
        // Arrange
        val cert = validEcCert().copy(san = listOf("other.com"), commonName = "example.com")

        // Act
        val result = ZetaCertificateValidator.validate(cert, now, host = "example.com")

        // Assert
        assertFalse(result.isValid)
    }
}
