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
package de.gematik.zeta.sdk.attestation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeviceAttestationEvidenceTest {
    @Test
    fun apple_equalsByContent() {
        val a = DeviceAttestationEvidence.Apple("key", byteArrayOf(1, 2, 3))
        val b = DeviceAttestationEvidence.Apple("key", byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun apple_notEqual_whenObjectDiffers() {
        assertNotEquals(
            DeviceAttestationEvidence.Apple("key", byteArrayOf(1, 2)),
            DeviceAttestationEvidence.Apple("key", byteArrayOf(1, 3)),
        )
    }

    @Test
    fun android_equalsByChainContent() {
        val a = DeviceAttestationEvidence.Android(listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        val b = DeviceAttestationEvidence.Android(listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun android_notEqual_whenChainDiffers() {
        assertNotEquals(
            DeviceAttestationEvidence.Android(listOf(byteArrayOf(1, 2))),
            DeviceAttestationEvidence.Android(listOf(byteArrayOf(1, 2), byteArrayOf(3))),
        )
    }

    @Test
    fun apple_notEqual_whenKeyIdOrTypeDiffers() {
        val apple = DeviceAttestationEvidence.Apple("key", byteArrayOf(1))
        assertEquals(apple, apple)
        assertNotEquals(apple, DeviceAttestationEvidence.Apple("other", byteArrayOf(1)))
        assertNotEquals<DeviceAttestationEvidence>(apple, DeviceAttestationEvidence.Android(listOf(byteArrayOf(1))))
    }

    @Test
    fun android_notEqual_whenSameSizeContentOrTypeDiffers() {
        val android = DeviceAttestationEvidence.Android(listOf(byteArrayOf(1)))
        assertEquals(android, android)
        assertNotEquals(android, DeviceAttestationEvidence.Android(listOf(byteArrayOf(2))))
        assertNotEquals<DeviceAttestationEvidence>(android, DeviceAttestationEvidence.Apple("key", byteArrayOf(1)))
    }

    @Test
    fun android_equalsIncludesPlayIntegrityToken() {
        val chain = listOf(byteArrayOf(1, 2))
        assertEquals(DeviceAttestationEvidence.Android(chain, "pit"), DeviceAttestationEvidence.Android(listOf(byteArrayOf(1, 2)), "pit"))
        assertEquals(
            DeviceAttestationEvidence.Android(chain, "pit").hashCode(),
            DeviceAttestationEvidence.Android(listOf(byteArrayOf(1, 2)), "pit").hashCode(),
        )
        assertNotEquals(DeviceAttestationEvidence.Android(chain, "pit"), DeviceAttestationEvidence.Android(chain, null))
    }
}
