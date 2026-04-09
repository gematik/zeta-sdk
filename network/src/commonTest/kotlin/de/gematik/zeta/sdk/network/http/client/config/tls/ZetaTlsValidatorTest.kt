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

class ZetaTlsValidatorTest {

    private val now = 1000L

    private fun validLeafCert() = ZetaCertInfo(
        subjectDN = "CN=leaf",
        sigAlgName = "SHA256WITHECDSA",
        keyAlgorithm = "EC",
        keySize = 256,
        curveName = "secp256r1",
        notBefore = 0,
        notAfter = 2000,
    )

    @Test
    fun validateNegotiatedCipherSuite_withTls13Suite_returnsIsCompliantTrue() {
        // Arrange
        val cipher = ZetaCipherSuites.AES_256_GCM_SHA384

        // Act
        val result = ZetaTlsValidator.validateNegotiatedCipherSuite(cipher)

        // Assert
        assertTrue(result.isCompliant)
    }

    @Test
    fun validateNegotiatedCipherSuite_withDisallowedCipher_returnsIsCompliantFalse() {
        // Arrange
        val cipher = "DES-CBC-SHA"

        // Act
        val result = ZetaTlsValidator.validateNegotiatedCipherSuite(cipher)

        // Assert
        assertFalse(result.isCompliant)
        assertTrue(result.errors.any { "not in the allowed list" in it })
    }

    @Test
    fun validateNegotiatedProtocol_withTls12_returnsIsCompliantTrue() {
        // Arrange
        val protocol = ZetaTlsProtocols.TLS_1_2

        // Act
        val result = ZetaTlsValidator.validateNegotiatedProtocol(protocol)

        // Assert
        assertTrue(result.isCompliant)
    }

    @Test
    fun validateNegotiatedProtocol_withTls13_returnsIsCompliantTrue() {
        // Arrange
        val protocol = ZetaTlsProtocols.TLS_1_3

        // Act
        val result = ZetaTlsValidator.validateNegotiatedProtocol(protocol)

        // Assert
        assertTrue(result.isCompliant)
    }

    @Test
    fun validateNegotiatedProtocol_withForbiddenProtocol_returnsIsCompliantFalse() {
        // Arrange
        val protocol = "SSLv3"

        // Act
        val result = ZetaTlsValidator.validateNegotiatedProtocol(protocol)

        // Assert
        assertFalse(result.isCompliant)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun validateNegotiatedProtocol_withUnknownProtocol_returnsIsCompliantFalse() {
        // Arrange
        val protocol = "TLSv99"

        // Act
        val result = ZetaTlsValidator.validateNegotiatedProtocol(protocol)

        // Assert
        assertFalse(result.isCompliant)
        assertTrue(result.errors.any { "not in the allowed list" in it })
    }

    @Test
    fun validateEnabledCipherSuites_withEmptyList_returnsIsCompliantTrue() {
        // Arrange
        val enabled = emptyList<String>()

        // Act
        val result = ZetaTlsValidator.validateEnabledCipherSuites(enabled)

        // Assert
        assertTrue(result.isCompliant)
    }

    @Test
    fun validateEnabledCipherSuites_withAllRequiredSuites_returnsIsCompliantTrue() {
        // Arrange
        val enabled = ZetaCipherSuites.REQUIRED_TLS_1_2

        // Act
        val result = ZetaTlsValidator.validateEnabledCipherSuites(enabled)

        // Assert
        assertTrue(result.isCompliant)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validateEnabledCipherSuites_withMissingRequiredSuite_returnsIsCompliantFalse() {
        // Arrange
        val enabled = listOf(ZetaCipherSuites.ECDHE_ECDSA_AES128_GCM_SHA256)

        // Act
        val result = ZetaTlsValidator.validateEnabledCipherSuites(enabled)

        // Assert
        assertFalse(result.isCompliant)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun validateEnabledCipherSuites_withIanaNamesForAllRequired_returnsIsCompliantTrue() {
        // Arrange
        val enabled = listOf(
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        )

        // Act
        val result = ZetaTlsValidator.validateEnabledCipherSuites(enabled)

        // Assert
        assertTrue(result.isCompliant)
    }

    @Test
    fun validateAll_withInvalidCipher_returnsIsCompliantFalse() {
        // Arrange
        val cipher = "DES-CBC-SHA"
        val protocol = ZetaTlsProtocols.TLS_1_2

        // Act
        val result = ZetaTlsValidator.validateAll(cipher, protocol, emptyList())

        // Assert
        assertFalse(result.isCompliant)
    }

    @Test
    fun validateAll_withNullCipherAndProtocol_returnsIsCompliantTrue() {
        // Arrange & Act
        val result = ZetaTlsValidator.validateAll(null, null, emptyList())

        // Assert
        assertTrue(result.isCompliant)
    }

    @Test
    fun validateAll_withInvalidLeafCert_returnsIsCompliantFalse() {
        // Arrange
        val cipher = ZetaCipherSuites.ECDHE_ECDSA_AES256_GCM_SHA384
        val protocol = ZetaTlsProtocols.TLS_1_3
        val expiredCert = validLeafCert().copy(notAfter = 500)

        // Act
        val result = ZetaTlsValidator.validateAll(cipher, protocol, emptyList(), expiredCert, now)

        // Assert
        assertFalse(result.isCompliant)
        assertTrue(result.errors.any { "expired" in it })
    }

    @Test
    fun validateHandshake_withNullCipherAndProtocol_returnsIsCompliantTrue() {
        // Arrange & Act
        val result = ZetaTlsValidator.validateHandshake(null, null)

        // Assert
        assertTrue(result.isCompliant)
    }

    @Test
    fun validateHandshake_withInvalidCipher_returnsIsCompliantFalse() {
        // Arrange
        val cipher = "RC4-MD5"

        // Act
        val result = ZetaTlsValidator.validateHandshake(cipher, ZetaTlsProtocols.TLS_1_2)

        // Assert
        assertFalse(result.isCompliant)
    }

    @Test
    fun validateHandshake_withForbiddenProtocol_returnsIsCompliantFalse() {
        // Arrange
        val protocol = "TLSv1.1"

        // Act
        val result = ZetaTlsValidator.validateHandshake(null, protocol)

        // Assert
        assertFalse(result.isCompliant)
    }

    @Test
    fun toOpenSslName_withTls13Suite_returnsSameName() {
        // Arrange
        val name = ZetaCipherSuites.AES_128_GCM_SHA256

        // Act
        val result = name.toOpenSslName()

        // Assert
        assertEquals(ZetaCipherSuites.AES_128_GCM_SHA256, result)
    }

    @Test
    fun toOpenSslName_withIanaEcdsaName_convertsToOpenSslFormat() {
        // Arrange
        val iana = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"

        // Act
        val result = iana.toOpenSslName()

        // Assert
        assertEquals(ZetaCipherSuites.ECDHE_ECDSA_AES128_GCM_SHA256, result)
    }

    @Test
    fun toOpenSslName_withIanaRsaName_convertsToOpenSslFormat() {
        // Arrange
        val iana = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"

        // Act
        val result = iana.toOpenSslName()

        // Assert
        assertEquals(ZetaCipherSuites.ECDHE_RSA_AES256_GCM_SHA384, result)
    }

    @Test
    fun validateNegotiatedCipherSuite_withAllowedCipherIanaFormat_returnsIsCompliantTrue() {
        // Arrange
        val cipher = "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"

        // Act
        val result = ZetaTlsValidator.validateNegotiatedCipherSuite(cipher)

        // Assert
        assertTrue(result.isCompliant)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validateNegotiatedCipherSuite_withAllowedRsaCipherIanaFormat_returnsIsCompliantTrue() {
        // Arrange
        val cipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"

        // Act
        val result = ZetaTlsValidator.validateNegotiatedCipherSuite(cipher)

        // Assert
        assertTrue(result.isCompliant)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun validateHandshake_withIanaCipherFormat_returnsIsCompliantTrue() {
        // Arrange
        val cipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
        val protocol = ZetaTlsProtocols.TLS_1_2

        // Act
        val result = ZetaTlsValidator.validateHandshake(cipher, protocol)

        // Assert
        assertTrue(result.isCompliant)
    }
}
