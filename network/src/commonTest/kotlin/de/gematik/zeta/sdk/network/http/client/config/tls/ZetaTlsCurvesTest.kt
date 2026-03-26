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

class ZetaTlsCurvesTest {

    @Test
    fun X25519_value_isX25519() {
        // Arrange & Act & Assert
        assertEquals("x25519", ZetaTlsCurves.X25519)
    }

    @Test
    fun P256_value_isSecp256r1() {
        // Arrange & Act & Assert
        assertEquals("secp256r1", ZetaTlsCurves.P256)
    }

    @Test
    fun P384_value_isSecp384r1() {
        // Arrange & Act & Assert
        assertEquals("secp384r1", ZetaTlsCurves.P384)
    }

    @Test
    fun BRAINPOOL_P256R1_value_isBrainpoolP256r1() {
        // Arrange & Act & Assert
        assertEquals("brainpoolP256r1", ZetaTlsCurves.BRAINPOOL_P256R1)
    }

    @Test
    fun BRAINPOOL_P384R1_value_isBrainpoolP384r1() {
        // Arrange & Act & Assert
        assertEquals("brainpoolP384r1", ZetaTlsCurves.BRAINPOOL_P384R1)
    }

    @Test
    fun ALLOWED_containsAllExpectedCurves_sizeIsFive() {
        // Arrange & Act
        val curves = ZetaTlsCurves.ALLOWED

        // Assert
        assertEquals(5, curves.size)
        assertTrue(ZetaTlsCurves.X25519 in curves)
        assertTrue(ZetaTlsCurves.P256 in curves)
        assertTrue(ZetaTlsCurves.P384 in curves)
        assertTrue(ZetaTlsCurves.BRAINPOOL_P256R1 in curves)
        assertTrue(ZetaTlsCurves.BRAINPOOL_P384R1 in curves)
    }
}
