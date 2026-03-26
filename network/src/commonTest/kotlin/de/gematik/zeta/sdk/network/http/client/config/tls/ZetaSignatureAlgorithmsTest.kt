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
import kotlin.test.assertTrue

class ZetaSignatureAlgorithmsTest {

    @Test
    fun ALLOWED_containsEcdsaAlgorithms_sizeIsThree() {
        // Arrange & Act
        val allowed = ZetaSignatureAlgorithms.ALLOWED

        // Assert
        assertEquals(3, allowed.size)
        assertTrue("ecdsa_secp256r1_sha256" in allowed)
        assertTrue("ecdsa_secp384r1_sha384" in allowed)
        assertTrue("ecdsa_secp521r1_sha512" in allowed)
    }

    @Test
    fun ALLOWED_WITH_RSA_includesAllEcdsaAndRsaAlgorithms_sizeIsSix() {
        // Arrange & Act
        val allowed = ZetaSignatureAlgorithms.ALLOWED_WITH_RSA

        // Assert
        assertEquals(6, allowed.size)
        assertTrue("rsa_pss_rsae_sha256" in allowed)
        assertTrue("rsa_pss_rsae_sha384" in allowed)
        assertTrue("rsa_pss_rsae_sha512" in allowed)
        ZetaSignatureAlgorithms.ALLOWED.forEach { assertTrue(it in allowed) }
    }

    @Test
    fun ALLOWED_OPENSSL_NAMES_containsExpectedNames_sizeIsThree() {
        // Arrange & Act
        val names = ZetaSignatureAlgorithms.ALLOWED_OPENSSL_NAMES

        // Assert
        assertEquals(3, names.size)
        assertTrue("RSA-SHA256" in names)
        assertTrue("ecdsa-with-SHA256" in names)
        assertTrue("ecdsa-with-SHA384" in names)
    }

    @Test
    fun FORBIDDEN_HASH_FUNCTIONS_containsForbiddenHashes_sizeIsThree() {
        // Arrange & Act
        val forbidden = ZetaSignatureAlgorithms.FORBIDDEN_HASH_FUNCTIONS

        // Assert
        assertEquals(3, forbidden.size)
        assertTrue("sha1" in forbidden)
        assertTrue("md5" in forbidden)
        assertTrue("sha224" in forbidden)
    }

    @Test
    fun ALLOWED_KEY_ALGORITHMS_containsRsaAndEc_sizeIsTwo() {
        // Arrange & Act
        val algorithms = ZetaSignatureAlgorithms.ALLOWED_KEY_ALGORITHMS

        // Assert
        assertEquals(2, algorithms.size)
        assertTrue("RSA" in algorithms)
        assertTrue("EC" in algorithms)
    }

    @Test
    fun MIN_RSA_KEY_BITS_value_is2048() {
        // Arrange & Act & Assert
        assertEquals(2048, ZetaSignatureAlgorithms.MIN_RSA_KEY_BITS)
    }

    @Test
    fun MIN_EC_KEY_BITS_value_is256() {
        // Arrange & Act & Assert
        assertEquals(256, ZetaSignatureAlgorithms.MIN_EC_KEY_BITS)
    }
}
