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

class ZetaTlsProtocolsTest {

    @Test
    fun TLS_1_2_value_isTlsv12() {
        // Arrange & Act & Assert
        assertEquals("TLSv1.2", ZetaTlsProtocols.TLS_1_2)
    }

    @Test
    fun TLS_1_3_value_isTlsv13() {
        // Arrange & Act & Assert
        assertEquals("TLSv1.3", ZetaTlsProtocols.TLS_1_3)
    }

    @Test
    fun ALLOWED_containsTls12AndTls13_sizeIsTwo() {
        // Arrange & Act
        val allowed = ZetaTlsProtocols.ALLOWED

        // Assert
        assertEquals(2, allowed.size)
        assertTrue(ZetaTlsProtocols.TLS_1_2 in allowed)
        assertTrue(ZetaTlsProtocols.TLS_1_3 in allowed)
    }

    @Test
    fun FORBIDDEN_containsLegacyProtocols_sizeIsFour() {
        // Arrange & Act
        val forbidden = ZetaTlsProtocols.FORBIDDEN

        // Assert
        assertEquals(4, forbidden.size)
        assertTrue("SSLv2" in forbidden)
        assertTrue("SSLv3" in forbidden)
        assertTrue("TLSv1" in forbidden)
        assertTrue("TLSv1.1" in forbidden)
    }

    @Test
    fun ALLOWED_doesNotContainForbiddenProtocols_noOverlap() {
        // Arrange
        val allowed = ZetaTlsProtocols.ALLOWED
        val forbidden = ZetaTlsProtocols.FORBIDDEN

        // Act
        val overlap = allowed.intersect(forbidden.toSet())

        // Assert
        assertTrue(overlap.isEmpty())
    }

    @Test
    fun FORBIDDEN_doesNotContainTls12_tls12IsAllowed() {
        // Arrange & Act & Assert
        assertFalse(ZetaTlsProtocols.TLS_1_2 in ZetaTlsProtocols.FORBIDDEN)
    }
}
