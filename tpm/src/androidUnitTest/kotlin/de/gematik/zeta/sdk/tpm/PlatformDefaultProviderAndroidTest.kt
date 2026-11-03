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

import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PlatformDefaultProviderAndroidTest {
    @Test
    fun android_returnsAndroidTpmProvider_andFallsBackToSoftwareOnHost() = runTest {
        val storage = TpmStorageImpl(InMemoryStorage(), ResourceScope("", emptyList()), clock = SystemZetaClock)
        val provider = platformDefaultProvider(storage, isHardwareAttestationSupported = true)
        assertIs<AndroidTpmProvider>(provider)
        // The host JVM has no AndroidKeyStore: the provider must degrade, not crash.
        // tpm/src/commonTest/kotlin/TpmProviderTest.kt also calls platformDefaultProvider on the Android host (software mode).
        assertFalse(provider.isHardwareBacked())
    }

    @Test
    fun bridge_reportsNoHardwareKeystore_onHost() {
        assertFalse(AndroidKeyStoreBridgeImpl { error("no context on host") }.hasHardwareKeystore())
    }
}
