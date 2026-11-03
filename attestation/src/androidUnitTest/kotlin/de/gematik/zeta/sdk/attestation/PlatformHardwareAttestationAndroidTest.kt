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

import de.gematik.zeta.android.SdkAndroidContext
import de.gematik.zeta.sdk.attestation.assertion.AndroidPostureBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PlatformHardwareAttestationAndroidTest {
    @Test
    fun android_providesKeyAttestation_unsupportedOnHost() {
        val hardware = assertNotNull(platformHardwareAttestation(AttestationStateStorage(InMemoryStorage())))
        assertEquals(HardwareAttestationType.ANDROID, hardware.attestor.attestationType)
        assertIs<AndroidPostureBuilder>(hardware.postureBuilder)
        assertFalse(hardware.attestor.isSupported())
    }

    @Test
    fun android_withPlayIntegrityProjectNumber_doesNotTouchContext() {
        SdkAndroidContext.init(mockk(relaxed = true), playIntegrityCloudProjectNumber = 880503825870L)
        try {
            val hardware = assertNotNull(platformHardwareAttestation(AttestationStateStorage(InMemoryStorage())))
            assertIs<AndroidPostureBuilder>(hardware.postureBuilder)
            assertFalse(hardware.attestor.isSupported())
        } finally {
            SdkAndroidContext.init(mockk(relaxed = true))
        }
    }
}
