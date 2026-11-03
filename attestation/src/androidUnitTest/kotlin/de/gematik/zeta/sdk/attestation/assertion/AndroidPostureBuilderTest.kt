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
package de.gematik.zeta.sdk.attestation.assertion

import de.gematik.zeta.sdk.attestation.AndroidAttestationInvalidKey
import de.gematik.zeta.sdk.attestation.AndroidDeviceAttestor
import de.gematik.zeta.sdk.attestation.AndroidDeviceInfo
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.FakeAndroidKeyStoreBridge
import de.gematik.zeta.sdk.attestation.FakePlayIntegrity
import de.gematik.zeta.sdk.attestation.PlayIntegrity
import de.gematik.zeta.sdk.attestation.RegisteredAttestationType
import de.gematik.zeta.sdk.attestation.model.AndroidBuild
import de.gematik.zeta.sdk.attestation.model.AndroidBuildVersion
import de.gematik.zeta.sdk.attestation.model.AndroidKeyguardManagerInfo
import de.gematik.zeta.sdk.attestation.model.AndroidPosture
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.attestation.model.PostureType
import de.gematik.zeta.sdk.attestation.newEcKeyPair
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.tpm.ecPublicKeyToPoint
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.concurrent.Executors
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPostureBuilderTest {
    private val clientPoint = ecPublicKeyToPoint(newEcKeyPair().public as ECPublicKey)
    private val nonce = byteArrayOf(9, 8, 7)
    private val productId = PlatformProductId.AndroidProductId(packageName = "de.gematik.app", sha256CertFingerprints = listOf("AA"))
    private val state = AttestationStateStorage(InMemoryStorage())
    private val bridge = FakeAndroidKeyStoreBridge()
    private val device = AndroidDeviceInfo(
        build = AndroidBuild(AndroidBuildVersion(34, "2026-08-05"), "Google", "husky", "Pixel 8 Pro", "husky"),
        ro = null,
        packageManager = null,
        keyguardManager = AndroidKeyguardManagerInfo(true),
        biometricManager = null,
        devicePolicyManager = null,
    )

    private suspend fun TestScope.attestedBuilder(playIntegrity: PlayIntegrity = PlayIntegrity.Disabled): AndroidPostureBuilder {
        val attestor = AndroidDeviceAttestor(state, bridge, StandardTestDispatcher(testScheduler))
        attestor.attest(clientPoint)
        state.setAttestationType(RegisteredAttestationType.ANDROID)
        return AndroidPostureBuilder(attestor, state, { device }, StandardTestDispatcher(testScheduler), playIntegrity)
    }

    @Test
    fun returnsNull_whenRegistrationIsNotAndroid() = runTest {
        val attestor = AndroidDeviceAttestor(state, bridge, StandardTestDispatcher(testScheduler))
        state.setAttestationType(RegisteredAttestationType.SOFTWARE)

        assertNull(AndroidPostureBuilder(attestor, state, { device }, StandardTestDispatcher(testScheduler)).build(clientPoint, nonce, productId, "demo", "1.0"))
    }

    @Test
    fun buildsAndroidPosture_withDeviceInfoAndType_withoutChain() = runTest {
        val result = attestedBuilder().build(clientPoint, nonce, productId, "demo", "1.0")!!

        assertEquals(PostureType.ANDROID, result.postureType)
        assertFalse("android_key_attestation_certificate_chain" in result.posture.jsonObject)
        val posture = hardwarePostureJson.decodeFromJsonElement(AndroidPosture.serializer(), result.posture)
        assertEquals("Pixel 8 Pro", posture.build.model)
        assertEquals(productId, posture.platformProductId)
    }

    @Test
    fun includesNonce_asBase64UrlString() = runTest {
        val result = attestedBuilder().build(clientPoint, nonce, productId, "demo", "1.0")!!

        val posture = hardwarePostureJson.decodeFromJsonElement(AndroidPosture.serializer(), result.posture)
        assertEquals("CQgH", posture.nonce)
    }

    @Test
    fun signature_verifiesOverNoncePostureWithoutSignatureAndClientKey() = runTest {
        val result = attestedBuilder().build(clientPoint, nonce, productId, "demo", "1.0")!!

        val posture = hardwarePostureJson.decodeFromJsonElement(AndroidPosture.serializer(), result.posture)
        val signature = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(posture.signedClientDataHash!!)
        val postureBytes = hardwarePostureJson
            .encodeToString(AndroidPosture.serializer(), posture.copy(signedClientDataHash = null))
            .encodeToByteArray()
        val akPublic = bridge.keys.getValue(state.getKeyId()!!).public
        assertTrue(
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(akPublic)
                update(hashWithSha256(nonce + postureBytes + clientPoint))
                verify(signature)
            },
        )
    }

    @Test
    fun throwsInvalidKey_whenAttestationKeyMissing() = runTest {
        val builder = attestedBuilder()
        bridge.delete(state.getKeyId()!!)

        assertFailsWith<AndroidAttestationInvalidKey> { builder.build(clientPoint, nonce, productId, "demo", "1.0") }
    }

    @Test
    fun collectsDeviceInfo_onTheConfiguredDispatcher() = runTest {
        val attestor = AndroidDeviceAttestor(state, bridge, StandardTestDispatcher(testScheduler))
        attestor.attest(clientPoint)
        state.setAttestationType(RegisteredAttestationType.ANDROID)
        var collectorThread: String? = null
        val executor = Executors.newSingleThreadExecutor { Thread(it, "posture-collector") }
        try {
            val builder = AndroidPostureBuilder(attestor, state, { device.also { collectorThread = Thread.currentThread().name } }, executor.asCoroutineDispatcher())

            builder.build(clientPoint, nonce, productId, "demo", "1.0")
        } finally {
            executor.shutdown()
        }

        assertEquals("posture-collector", collectorThread)
    }

    @Test
    fun rejectsNonAndroidProductId() = runTest {
        val apple = PlatformProductId.AppleProductId("apple", "ios", listOf())

        assertFailsWith<IllegalStateException> { attestedBuilder().build(clientPoint, nonce, apple, "demo", "1.0") }
    }

    @Test
    fun requestsStandardPlayIntegrityToken_boundToClientDataHash() = runTest {
        val playIntegrity = FakePlayIntegrity("pit")

        val result = attestedBuilder(playIntegrity).build(clientPoint, nonce, productId, "demo", "1.0")!!

        val posture = hardwarePostureJson.decodeFromJsonElement(AndroidPosture.serializer(), result.posture)
        assertEquals("pit", posture.playIntegrityToken)
        val postureBytes = hardwarePostureJson
            .encodeToString(AndroidPosture.serializer(), posture.copy(signedClientDataHash = null, playIntegrityToken = null))
            .encodeToByteArray()
        val clientDataHash = hashWithSha256(nonce + postureBytes + clientPoint)
        assertEquals(
            listOf(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(clientDataHash)),
            playIntegrity.requestHashes,
        )
    }

    @Test
    fun signature_doesNotCoverPlayIntegrityToken() = runTest {
        val result = attestedBuilder(FakePlayIntegrity("pit")).build(clientPoint, nonce, productId, "demo", "1.0")!!

        val posture = hardwarePostureJson.decodeFromJsonElement(AndroidPosture.serializer(), result.posture)
        val signature = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(posture.signedClientDataHash!!)
        val postureBytes = hardwarePostureJson
            .encodeToString(AndroidPosture.serializer(), posture.copy(signedClientDataHash = null, playIntegrityToken = null))
            .encodeToByteArray()
        val akPublic = bridge.keys.getValue(state.getKeyId()!!).public
        assertTrue(
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(akPublic)
                update(hashWithSha256(nonce + postureBytes + clientPoint))
                verify(signature)
            },
        )
    }

    @Test
    fun omitsPlayIntegrityToken_whenUnavailable() = runTest {
        val result = attestedBuilder(FakePlayIntegrity(null)).build(clientPoint, nonce, productId, "demo", "1.0")!!

        assertFalse("play_integrity_token" in result.posture.jsonObject)
    }
}
