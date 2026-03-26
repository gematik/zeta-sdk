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

class ZetaCipherSuitesTest {

    @Test
    fun ECDHE_ECDSA_AES128_GCM_SHA256_value_isExpectedOpenSslName() {
        // Arrange & Act & Assert
        assertEquals("ECDHE-ECDSA-AES128-GCM-SHA256", ZetaCipherSuites.ECDHE_ECDSA_AES128_GCM_SHA256)
    }

    @Test
    fun ECDHE_ECDSA_AES256_GCM_SHA384_value_isExpectedOpenSslName() {
        // Arrange & Act & Assert
        assertEquals("ECDHE-ECDSA-AES256-GCM-SHA384", ZetaCipherSuites.ECDHE_ECDSA_AES256_GCM_SHA384)
    }

    @Test
    fun AES_128_GCM_SHA256_value_isTls13Name() {
        // Arrange & Act & Assert
        assertEquals("TLS_AES_128_GCM_SHA256", ZetaCipherSuites.AES_128_GCM_SHA256)
    }

    @Test
    fun AES_256_GCM_SHA384_value_isTls13Name() {
        // Arrange & Act & Assert
        assertEquals("TLS_AES_256_GCM_SHA384", ZetaCipherSuites.AES_256_GCM_SHA384)
    }

    @Test
    fun REQUIRED_TLS_1_2_containsAllFourSuites_sizeIsFour() {
        // Arrange & Act
        val suites = ZetaCipherSuites.REQUIRED_TLS_1_2

        // Assert
        assertEquals(4, suites.size)
        assertTrue(ZetaCipherSuites.ECDHE_ECDSA_AES128_GCM_SHA256 in suites)
        assertTrue(ZetaCipherSuites.ECDHE_ECDSA_AES256_GCM_SHA384 in suites)
        assertTrue(ZetaCipherSuites.ECDHE_RSA_AES128_GCM_SHA256 in suites)
        assertTrue(ZetaCipherSuites.ECDHE_RSA_AES256_GCM_SHA384 in suites)
    }

    @Test
    fun TLS_1_3_SUITES_containsBothSuites_sizeIsTwo() {
        // Arrange & Act
        val suites = ZetaCipherSuites.TLS_1_3_SUITES

        // Assert
        assertEquals(2, suites.size)
        assertTrue(ZetaCipherSuites.AES_128_GCM_SHA256 in suites)
        assertTrue(ZetaCipherSuites.AES_256_GCM_SHA384 in suites)
    }

    @Test
    fun FULL_PREFERRED_ORDER_combinesTls12AndTls13_sizeIsSix() {
        // Arrange & Act
        val suites = ZetaCipherSuites.FULL_PREFERRED_ORDER

        // Assert
        assertEquals(6, suites.size)
        assertEquals(ZetaCipherSuites.REQUIRED_TLS_1_2 + ZetaCipherSuites.TLS_1_3_SUITES, suites)
    }

    @Test
    fun FULL_PREFERRED_ORDER_IANA_mapsAllSuitesToIanaNames_sizeIsSix() {
        // Arrange & Act
        val iana = ZetaCipherSuites.FULL_PREFERRED_ORDER_IANA

        // Assert
        assertEquals(6, iana.size)
        assertTrue("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256" in iana)
        assertTrue("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384" in iana)
        assertTrue("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" in iana)
        assertTrue("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384" in iana)
        assertTrue("TLS_AES_128_GCM_SHA256" in iana)
        assertTrue("TLS_AES_256_GCM_SHA384" in iana)
    }
}
