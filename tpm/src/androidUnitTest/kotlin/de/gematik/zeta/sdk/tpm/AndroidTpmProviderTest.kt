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

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.crypto.sha256PublicKeyPoint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTpmProviderTest {
    private val softwareKey = PublicKeyOut(ByteArray(91) { 7 }, Jwk("sw-kid", "EC", "ES256", "sig", "P-256", "x", "y"))
    private val software = mockk<TpmProvider>(relaxed = true).also {
        coEvery { it.getOrGenerateClientInstancePublicKey() } returns softwareKey
    }

    @Test
    fun hardwareMode_generatesClientKeyInStrongBox_andExposesPoint() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = true, dispatcher = StandardTestDispatcher(testScheduler))

        val key = sut.getOrGenerateClientInstancePublicKey()

        assertTrue(sut.isHardwareBacked())
        assertEquals(65, key.encoded.size)
        assertContentEquals(bridge.publicKeyPoint(AndroidKeyAliases.CLIENT_INSTANCE_KEY), key.encoded)
        assertEquals(listOf(AndroidKeyAliases.CLIENT_INSTANCE_KEY to true), bridge.generateCalls)
    }

    @Test
    fun hardwareMode_reusesExistingClientKey() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        bridge.generateEcKey(AndroidKeyAliases.CLIENT_INSTANCE_KEY, null, strongBox = false)
        bridge.generateCalls.clear()
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = true, dispatcher = StandardTestDispatcher(testScheduler))

        sut.getOrGenerateClientInstancePublicKey()

        assertTrue(bridge.generateCalls.isEmpty())
    }

    @Test
    fun softwareBackedKey_fallsBackToSoftwareMode() = runTest {
        val bridge = FakeAndroidKeyStoreBridge(forcedLevel = KeySecurityLevel.SOFTWARE)
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = true, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(softwareKey, sut.getOrGenerateClientInstancePublicKey())
        assertFalse(sut.isHardwareBacked())
        assertFalse(bridge.contains(AndroidKeyAliases.CLIENT_INSTANCE_KEY))
    }

    @Test
    fun hardwareDisabled_usesSoftwareDelegate_withoutTouchingKeystore() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = false, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(softwareKey, sut.getOrGenerateClientInstancePublicKey())
        assertTrue(bridge.generateCalls.isEmpty())
    }

    @Test
    fun proofOfPossession_verifiesAsSignatureOverHashOfPoint() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = true, dispatcher = StandardTestDispatcher(testScheduler))
        val point = sut.getOrGenerateClientInstancePublicKey().encoded

        val pop = sut.signProofOfPossession()

        val public = bridge.keys.getValue(AndroidKeyAliases.CLIENT_INSTANCE_KEY).public
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(public); update(point); verify(pop) })
        assertTrue(Signature.getInstance("NONEwithECDSA").run { initVerify(public); update(sha256PublicKeyPoint(point)); verify(pop) })
    }

    @Test
    fun signWithClientKey_returnsJose64Bytes() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = true, dispatcher = StandardTestDispatcher(testScheduler))
        sut.getOrGenerateClientInstancePublicKey()

        assertEquals(64, sut.signWithClientKey("header.payload".encodeToByteArray()).size)
    }

    @Test
    fun proofOfPossession_inSoftwareMode_fails() = runTest {
        val sut = AndroidTpmProvider(FakeAndroidKeyStoreBridge(), software, useHardwareClientKey = false, dispatcher = StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { sut.signProofOfPossession() }
    }

    @Test
    fun forgetAll_deletesClientKey_andDelegates() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = true, dispatcher = StandardTestDispatcher(testScheduler))
        sut.getOrGenerateClientInstancePublicKey()

        sut.forget()

        assertFalse(bridge.contains(AndroidKeyAliases.CLIENT_INSTANCE_KEY))
        coVerify { software.forget(null) }
    }

    @Test
    fun forgetAll_toleratesKeystoreErrorOnDelete_andStillDelegates() = runTest {
        val bridge = FakeAndroidKeyStoreBridge(throwOnDelete = true)
        val sut = AndroidTpmProvider(bridge, software, useHardwareClientKey = true, dispatcher = StandardTestDispatcher(testScheduler))
        sut.getOrGenerateClientInstancePublicKey()

        sut.forget()

        coVerify { software.forget(null) }
    }
}
