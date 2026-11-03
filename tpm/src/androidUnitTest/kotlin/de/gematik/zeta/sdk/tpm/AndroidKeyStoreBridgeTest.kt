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

package de.gematik.zeta.sdk.tpm

import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidKeyStoreBridgeTest {
    @Test
    fun ecPublicKeyToPoint_is65ByteUncompressedPoint() {
        repeat(20) {
            val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
            val point = ecPublicKeyToPoint(key)
            assertEquals(65, point.size)
            assertEquals(0x04.toByte(), point[0])
            assertEquals(key.w.affineX, java.math.BigInteger(1, point.copyOfRange(1, 33)))
            assertEquals(key.w.affineY, java.math.BigInteger(1, point.copyOfRange(33, 65)))
        }
    }

    @Test
    fun generateHardwareEcKey_prefersStrongBox() {
        val bridge = FakeAndroidKeyStoreBridge()
        assertTrue(generateHardwareEcKey(bridge, "a", null))
        assertEquals(listOf("a" to true), bridge.generateCalls)
    }

    @Test
    fun generateHardwareEcKey_fallsBackToTee_whenStrongBoxUnavailable() {
        val bridge = FakeAndroidKeyStoreBridge(strongBoxAvailable = false)
        assertTrue(generateHardwareEcKey(bridge, "a", null))
        assertEquals(listOf("a" to true, "a" to false), bridge.generateCalls)
    }

    @Test
    fun generateHardwareEcKey_deletesSoftwareBackedKey_andReturnsFalse() {
        val bridge = FakeAndroidKeyStoreBridge(forcedLevel = KeySecurityLevel.SOFTWARE)
        assertFalse(generateHardwareEcKey(bridge, "a", null))
        assertFalse(bridge.contains("a"))
    }
}
