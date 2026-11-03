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

import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.crypto.sha256PublicKeyPoint
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.tpm.AndroidKeyAliases.ATTESTATION_KEY
import de.gematik.zeta.sdk.tpm.KeySecurityLevel
import de.gematik.zeta.sdk.tpm.ecPublicKeyToPoint
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDeviceAttestorTest {
    private val clientPoint = ecPublicKeyToPoint(newEcKeyPair().public as ECPublicKey)
    private val state = AttestationStateStorage(InMemoryStorage())

    private fun TestScope.attestor(bridge: FakeAndroidKeyStoreBridge) =
        AndroidDeviceAttestor(state, bridge, StandardTestDispatcher(testScheduler))

    private suspend fun keyId(): String = requireNotNull(state.getKeyId())

    /** dcr-request.yaml 1.1.1 definitions/hash_signature: SHA256withECDSA with the 32-byte hash as the message. */
    private fun verifiesHashSignature(publicKey: PublicKey, hash: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey); update(hash); verify(signature) }

    private fun verifiesRawDigestSignature(publicKey: PublicKey, hash: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("NONEwithECDSA").run { initVerify(publicKey); update(hash); verify(signature) }

    @Test
    fun attest_generatesAkBoundToClientKey_andStoresChain() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()

        val evidence = attestor(bridge).attest(clientPoint)

        assertIs<DeviceAttestationEvidence.Android>(evidence)
        assertEquals(DeviceAttestationEvidence.Android(THREE_CERT_CHAIN), evidence)
        val alias = keyId()
        assertEquals(ATTESTATION_KEY, alias)
        assertContentEquals(sha256PublicKeyPoint(clientPoint), bridge.challenges.getValue(alias))
        assertEquals(3, state.getCertificateChain()!!.size)
    }

    @Test
    fun attest_fallsBackToTee_whenStrongBoxUnavailable() = runTest {
        val bridge = FakeAndroidKeyStoreBridge(strongBoxAvailable = false)

        attestor(bridge).attest(clientPoint)

        assertEquals(listOf(keyId() to true, keyId() to false), bridge.generateCalls)
    }

    @Test
    fun attest_removesZetaAttestationToken() = runTest {
        state.setZetaAttestationToken("old-zat")

        attestor(FakeAndroidKeyStoreBridge()).attest(clientPoint)

        assertNull(state.getZetaAttestationToken())
    }

    @Test
    fun attest_unsupported_throws_withoutGenerating() = runTest {
        val bridge = FakeAndroidKeyStoreBridge(hardwareKeystore = false)

        assertFailsWith<AndroidAttestationUnsupported> { attestor(bridge).attest(clientPoint) }
        assertTrue(bridge.generateCalls.isEmpty())
    }

    @Test
    fun attest_softwareBackedAk_throwsUnsupported_andLeavesNoKey() = runTest {
        val bridge = FakeAndroidKeyStoreBridge(forcedLevel = KeySecurityLevel.SOFTWARE)

        assertFailsWith<AndroidAttestationUnsupported> { attestor(bridge).attest(clientPoint) }
        assertTrue(bridge.keys.isEmpty())
        assertNull(state.getKeyId())
    }

    @Test
    fun attest_shortChain_throwsUnsupported_andDeletesAk() = runTest {
        val bridge = FakeAndroidKeyStoreBridge(chain = listOf(byteArrayOf(1)))

        assertFailsWith<AndroidAttestationUnsupported> { attestor(bridge).attest(clientPoint) }
        assertTrue(bridge.keys.isEmpty())
        assertNull(state.getKeyId())
        assertNull(state.getCertificateChain())
    }

    @Test
    fun attest_again_replacesTheAttestationKey() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        sut.attest(clientPoint)
        val firstAk = bridge.keys.getValue(ATTESTATION_KEY).public

        sut.attest(clientPoint)

        assertEquals(ATTESTATION_KEY, keyId())
        assertEquals(setOf(ATTESTATION_KEY), bridge.keys.keys)
        assertNotEquals(firstAk, bridge.keys.getValue(ATTESTATION_KEY).public)
    }

    @Test
    fun forget_ignoresForeignStoredKeyId() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        bridge.generateEcKey("Alias_Client_Sig", null, strongBox = false)
        state.setKeyId("Alias_Client_Sig")

        attestor(bridge).forget()

        assertTrue(bridge.contains("Alias_Client_Sig"))
    }

    @Test
    fun isSupported_false_whenBridgeThrows() = runTest {
        assertFalse(attestor(FakeAndroidKeyStoreBridge(throwOnHasHardware = true)).isSupported())
    }

    @Test
    fun hasAttestedKey_trueOnlyAfterAttest_andWhileAkExists() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        assertFalse(sut.hasAttestedKey())

        sut.attest(clientPoint)
        assertTrue(sut.hasAttestedKey())

        bridge.delete(keyId())
        assertFalse(sut.hasAttestedKey())
    }

    @Test
    fun proveAttestationKey_isAkSignatureOverClientKeyHash() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        sut.attest(clientPoint)
        val hashPukClientSig = sha256PublicKeyPoint(clientPoint)

        val pop = sut.proveAttestationKey(hashPukClientSig)

        val akPublic = bridge.keys.getValue(keyId()).public
        assertTrue(verifiesHashSignature(akPublic, hashPukClientSig, pop))
        assertFalse(verifiesRawDigestSignature(akPublic, hashPukClientSig, pop))
    }

    @Test
    fun runtimeEvidence_isAkSignatureOverClientDataHash() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        sut.attest(clientPoint)
        val clientDataHash = hashWithSha256("nonce-posture-key".encodeToByteArray())

        val signature = sut.runtimeEvidence(keyId(), clientDataHash)

        val akPublic = bridge.keys.getValue(keyId()).public
        assertTrue(verifiesHashSignature(akPublic, clientDataHash, signature))
        assertFalse(verifiesRawDigestSignature(akPublic, clientDataHash, signature))
    }

    @Test
    fun runtimeEvidence_invalidatedAk_removesAttestedKey_andThrowsInvalidKey() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        sut.attest(clientPoint)
        val alias = keyId()
        bridge.invalidated += alias

        assertFailsWith<AndroidAttestationInvalidKey> { sut.runtimeEvidence(alias, ByteArray(32)) }
        assertNull(state.getKeyId())
        assertNull(state.getCertificateChain())
        assertFalse(bridge.contains(alias))
    }

    @Test
    fun runtimeEvidence_keyIdMismatch_throwsInvalidKey_andKeepsKey() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        sut.attest(clientPoint)
        val alias = keyId()

        assertFailsWith<AndroidAttestationInvalidKey> { sut.runtimeEvidence("other", ByteArray(32)) }
        assertEquals(alias, state.getKeyId())
        assertTrue(bridge.contains(alias))
    }

    @Test
    fun forget_deletesTheStoredAlias() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        sut.attest(clientPoint)
        val alias = keyId()

        sut.forget()

        assertFalse(bridge.contains(alias))
    }

    @Test
    fun forget_toleratesKeystoreErrors() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val sut = attestor(bridge)
        sut.attest(clientPoint)
        bridge.throwOnDelete = true

        sut.forget()

        assertTrue(bridge.contains(keyId()))
    }

    @Test
    fun forget_withoutAttestedKey_doesNothing() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()

        attestor(bridge).forget()

        assertTrue(bridge.keys.isEmpty())
    }

    @Test
    fun attest_requestsClassicPlayIntegrityToken_boundToClientKeyHash() = runTest {
        val playIntegrity = FakePlayIntegrity("pit")

        val evidence = AndroidDeviceAttestor(state, FakeAndroidKeyStoreBridge(), StandardTestDispatcher(testScheduler), playIntegrity)
            .attest(clientPoint)

        val expectedNonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(sha256PublicKeyPoint(clientPoint))
        assertEquals(listOf(expectedNonce), playIntegrity.nonces)
        assertEquals(DeviceAttestationEvidence.Android(THREE_CERT_CHAIN, "pit"), evidence)
    }

    @Test
    fun attest_withoutPlayIntegrityToken_stillSucceeds_andKeepsAk() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()

        val evidence = AndroidDeviceAttestor(state, bridge, StandardTestDispatcher(testScheduler), FakePlayIntegrity(null))
            .attest(clientPoint)

        assertEquals(DeviceAttestationEvidence.Android(THREE_CERT_CHAIN, null), evidence)
        assertEquals(ATTESTATION_KEY, keyId())
        assertTrue(bridge.contains(ATTESTATION_KEY))
    }

    @Test
    fun attest_cancelledDuringPlayIntegrityRequest_keepsTheAk() = runTest {
        val bridge = FakeAndroidKeyStoreBridge()
        val hangingPlayIntegrity = object : PlayIntegrity {
            override suspend fun requestIntegrityToken(nonce: String): String? = awaitCancellation()
            override suspend fun requestStandardToken(requestHash: String): String? = null
        }

        val attestor = AndroidDeviceAttestor(state, bridge, StandardTestDispatcher(testScheduler), hangingPlayIntegrity)
        val job = launch { attestor.attest(clientPoint) }
        testScheduler.advanceUntilIdle()
        job.cancel()
        job.join()

        assertTrue(bridge.contains(ATTESTATION_KEY))
        assertEquals(ATTESTATION_KEY, state.getKeyId())
        assertNotNull(state.getCertificateChain())
    }
}
