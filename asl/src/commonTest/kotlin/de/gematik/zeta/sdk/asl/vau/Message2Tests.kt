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

package de.gematik.zeta.sdk.asl.vau

import de.gematik.zeta.sdk.asl.AslCertDataApi
import de.gematik.zeta.sdk.asl.AslStorage
import de.gematik.zeta.sdk.asl.AslStorageImpl
import de.gematik.zeta.sdk.asl.CertData
import de.gematik.zeta.sdk.asl.EncapsulationResult
import de.gematik.zeta.sdk.asl.EstablishedSession
import de.gematik.zeta.sdk.asl.M3InnerLayer
import de.gematik.zeta.sdk.asl.Message2
import de.gematik.zeta.sdk.asl.SignedVauPublicKeys
import de.gematik.zeta.sdk.asl.VauKeys
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import de.gematik.zeta.sdk.crypto.EcPointP256
import de.gematik.zeta.sdk.crypto.hashWithSha256
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.config.tls.sanMatchesHost
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class Message2And3Test {
    val requiredOid = "1.2.276.0.76.4.261"
    private val day = SECONDS_PER_DAY.toLong()
    private val thirtyDays = MAX_KEY_LIFETIME_SECONDS.toLong()
    private val now = 1_700_000_000L

    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor {
        alwaysUseByteString = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun parseMessage2_returnsMessage2_validCbor() {
        // Arrange
        val message2 = Message2(
            type = "M2",
            ecdhCiphertext = EcPointP256("P-256", ByteArray(32), ByteArray(32)),
            ml768Ciphertext = ByteArray(32),
            aeadCiphertext = ByteArray(32),
        )
        val encoded = cbor.encodeToByteArray(Message2.serializer(), message2)

        // Act
        val result = parseMessage2(encoded)

        // Assert
        assertEquals("M2", result.type)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun parseMessage2_throwsException_wrongType() {
        // Arrange
        val message2 = Message2(
            type = "M3",
            ecdhCiphertext = EcPointP256("P-256", ByteArray(32), ByteArray(32)),
            ml768Ciphertext = ByteArray(32),
            aeadCiphertext = ByteArray(32),
        )
        val encoded = cbor.encodeToByteArray(Message2.serializer(), message2)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            parseMessage2(encoded)
        }
    }

    @Test
    fun toUncompressedPoint_returns65Bytes_validPoint() {
        // Arrange
        val x = ByteArray(32) { 1 }
        val y = ByteArray(32) { 2 }
        val point = EcPointP256("P-256", x, y)

        // Act
        val result = point.toUncompressedPoint()

        // Assert
        assertEquals(65, result.size)
        assertEquals(0x04.toByte(), result[0])
        assertContentEquals(x, result.copyOfRange(1, 33))
        assertContentEquals(y, result.copyOfRange(33, 65))
    }

    @Test
    fun toUncompressedPoint_throwsException_wrongCurve() {
        // Arrange
        val point = EcPointP256("P-384", ByteArray(32), ByteArray(32))

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            point.toUncompressedPoint()
        }
    }

    @Test
    fun toUncompressedPoint_throwsException_wrongXSize() {
        // Arrange
        val point = EcPointP256("P-256", ByteArray(31), ByteArray(32))

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            point.toUncompressedPoint()
        }
    }

    @Test
    fun toUncompressedPoint_throwsException_wrongYSize() {
        // Arrange
        val point = EcPointP256("P-256", ByteArray(32), ByteArray(31))

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            point.toUncompressedPoint()
        }
    }

    @Test
    fun fromKemBytes_returnsEcPoint_validBytes() {
        // Arrange
        val bytes = ByteArray(65)
        bytes[0] = 0x04.toByte()
        for (i in 1..32) bytes[i] = i.toByte()
        for (i in 33..64) bytes[i] = (i + 100).toByte()

        // Act
        val result = EcPointP256.fromKemBytes(bytes)

        // Assert
        assertEquals("P-256", result.crv)
        assertEquals(32, result.x.size)
        assertEquals(32, result.y.size)
        assertContentEquals(bytes.copyOfRange(1, 33), result.x)
        assertContentEquals(bytes.copyOfRange(33, 65), result.y)
    }

    @Test
    fun fromKemBytes_throwsException_wrongSize() {
        // Arrange
        val bytes = ByteArray(64)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            EcPointP256.fromKemBytes(bytes)
        }
    }

    @Test
    fun fromKemBytes_throwsException_tooLarge() {
        // Arrange
        val bytes = ByteArray(66)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            EcPointP256.fromKemBytes(bytes)
        }
    }

    @Test
    fun deriveSharedKeys_returns64BytesSplit_validInput() {
        // Arrange
        val ephemeralSS = ByteArray(64) { it.toByte() }

        // Act
        val (k1_c2s, k1_s2c) = deriveSharedKeys(ephemeralSS)

        // Assert
        assertEquals(32, k1_c2s.size)
        assertEquals(32, k1_s2c.size)
    }

    @Test
    fun deriveSharedKeys_usesHkdfCorrectly_validInput() {
        // Arrange
        val ephemeralSS = ByteArray(64) { 0xFF.toByte() }

        // Act
        val (k1_c2s, k1_s2c) = deriveSharedKeys(ephemeralSS)

        // Assert
        val allZerosC2s = k1_c2s.all { it == 0.toByte() }
        val allZerosS2c = k1_s2c.all { it == 0.toByte() }
        assertEquals(false, allZerosC2s)
        assertEquals(false, allZerosS2c)
    }

    @Test
    fun deriveK2_returnsK2Keys_validInput() {
        // Arrange
        val ephemeralSS = ByteArray(64) { 1 }
        val serverSS = ByteArray(64) { 2 }

        // Act
        val result = deriveK2(ephemeralSS, serverSS)

        // Assert
        assertEquals(32, result.clientToServerConfirmationKey.size)
        assertEquals(32, result.clientToServerAppDataKey.size)
        assertEquals(32, result.serverToClientConfirmationKey.size)
        assertEquals(32, result.serverToClientAppDataKey.size)
        assertEquals(32, result.keyId.size)
        assertEquals(160, result.outputKeyingMaterial160.size)
    }

    @Test
    fun deriveK2_extractsKeysCorrectly_validInput() {
        // Arrange
        val ephemeralSS = ByteArray(64) { 0xAA.toByte() }
        val serverSS = ByteArray(64) { 0xBB.toByte() }

        // Act
        val result = deriveK2(ephemeralSS, serverSS)

        // Assert
        // Verify that keys are extracted from okm at correct offsets
        assertContentEquals(
            result.outputKeyingMaterial160.copyOfRange(0, 32),
            result.clientToServerConfirmationKey,
        )
        assertContentEquals(
            result.outputKeyingMaterial160.copyOfRange(32, 64),
            result.clientToServerAppDataKey,
        )
        assertContentEquals(
            result.outputKeyingMaterial160.copyOfRange(64, 96),
            result.serverToClientConfirmationKey,
        )
        assertContentEquals(
            result.outputKeyingMaterial160.copyOfRange(96, 128),
            result.serverToClientAppDataKey,
        )
        assertContentEquals(
            result.outputKeyingMaterial160.copyOfRange(128, 160),
            result.keyId,
        )
    }

    @Test
    fun buildM3InnerLayer_setsErpAndEsoToFalse_validInput() {
        // Arrange
        val encaps = EncapsulationResult(
            serverSharedSecret = ByteArray(64),
            ecdhCiphertext = ByteArray(65).apply { this[0] = 0x04.toByte() },
            mlKemCiphertext = ByteArray(32),
        )

        // Act
        val result = buildM3InnerLayer(encaps)

        // Assert
        assertEquals(false, result.erpEnabled)
        assertEquals(false, result.esoEnabled)
    }

    @Test
    fun buildM3InnerLayer_convertsCiphertext_validInput() {
        // Arrange
        val ecdhCt = ByteArray(65)
        ecdhCt[0] = 0x04.toByte()
        for (i in 1..32) ecdhCt[i] = 1.toByte()
        for (i in 33..64) ecdhCt[i] = 2.toByte()

        val encaps = EncapsulationResult(
            serverSharedSecret = ByteArray(64),
            ecdhCiphertext = ecdhCt,
            mlKemCiphertext = ByteArray(32) { 3 },
        )

        // Act
        val result = buildM3InnerLayer(encaps)

        // Assert
        assertEquals("P-256", result.ecdhCiphertext.crv)
        assertContentEquals(ecdhCt.copyOfRange(1, 33), result.ecdhCiphertext.x)
        assertContentEquals(ecdhCt.copyOfRange(33, 65), result.ecdhCiphertext.y)
        assertContentEquals(ByteArray(32) { 3 }, result.mlKemCiphertext)
    }

    @Test
    fun computeTranscriptHash_concatenatesAndHashes_validInput() {
        // Arrange
        val m1 = byteArrayOf(1, 2, 3)
        val m2 = byteArrayOf(4, 5, 6)
        val m3 = byteArrayOf(7, 8, 9)

        // Act
        val result = computeTranscriptHash(m1, m2, m3)

        // Assert
        val expected = hashWithSha256(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        assertContentEquals(expected, result)
    }

    @Test
    fun computeTranscriptHash_returns32Bytes_validInput() {
        // Arrange
        val m1 = ByteArray(100) { 0xAA.toByte() }
        val m2 = ByteArray(200) { 0xBB.toByte() }
        val m3 = ByteArray(150) { 0xCC.toByte() }

        // Act
        val result = computeTranscriptHash(m1, m2, m3)

        // Assert
        assertEquals(32, result.size)
    }

    @Test
    fun encryptKeyConfirmation_returnsEncryptedData_validInput() {
        // Arrange
        val key = ByteArray(32) { 1 }
        val hash = ByteArray(32) { 2 }

        // Act
        val result = encryptKeyConfirmation(key, hash)

        // Assert
        assertTrue(result.size > hash.size)
    }

    @Test
    fun encryptKeyConfirmation_canBeDecrypted_validInput() {
        // Arrange
        val key = ByteArray(32) { 0x42.toByte() }
        val hash = ByteArray(32) { 0x99.toByte() }

        // Act
        val encrypted = encryptKeyConfirmation(key, hash)
        val decrypted = AesGcmCipherImpl().decrypt(key, encrypted)

        // Assert
        assertContentEquals(hash, decrypted)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun parseMessage2_preservesAllFields_validCbor() {
        val ecdh = EcPointP256("P-256", ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val ml = ByteArray(32) { 3 }
        val aead = ByteArray(32) { 4 }
        val message2 = Message2(type = "M2", ecdhCiphertext = ecdh, ml768Ciphertext = ml, aeadCiphertext = aead)
        val encoded = cbor.encodeToByteArray(Message2.serializer(), message2)

        val result = parseMessage2(encoded)

        assertContentEquals(ByteArray(32) { 1 }, result.ecdhCiphertext.x)
        assertContentEquals(ByteArray(32) { 2 }, result.ecdhCiphertext.y)
        assertContentEquals(ml, result.ml768Ciphertext)
        assertContentEquals(aead, result.aeadCiphertext)
    }

    @Test
    fun toUncompressedPoint_throwsException_bothCoordinatesWrongSize() {
        val point = EcPointP256("P-256", ByteArray(31), ByteArray(31))
        assertFailsWith<IllegalArgumentException> {
            point.toUncompressedPoint()
        }
    }

    @Test
    fun toUncompressedPoint_firstByteIs04_validPoint() {
        val point = EcPointP256("P-256", ByteArray(32) { 0xAA.toByte() }, ByteArray(32) { 0xBB.toByte() })
        val result = point.toUncompressedPoint()
        assertEquals(0x04.toByte(), result[0])
    }

    @Test
    fun toUncompressedPoint_roundTrip_fromKemBytes() {
        val x = ByteArray(32) { it.toByte() }
        val y = ByteArray(32) { (it + 32).toByte() }
        val point = EcPointP256("P-256", x, y)

        val uncompressed = point.toUncompressedPoint()
        val restored = EcPointP256.fromKemBytes(uncompressed)

        assertContentEquals(x, restored.x)
        assertContentEquals(y, restored.y)
        assertEquals("P-256", restored.crv)
    }

    @Test
    fun fromKemBytes_throwsException_emptyArray() {
        assertFailsWith<IllegalArgumentException> {
            EcPointP256.fromKemBytes(ByteArray(0))
        }
    }

    @Test
    fun deriveSharedKeys_keysAreDifferentFromEachOther_validInput() {
        val ephemeralSS = ByteArray(64) { it.toByte() }
        val (k1_c2s, k1_s2c) = deriveSharedKeys(ephemeralSS)
        assertFalse(k1_c2s.contentEquals(k1_s2c))
    }

    @Test
    fun deriveSharedKeys_isDeterministic_sameInput() {
        val ephemeralSS = ByteArray(64) { 0x42.toByte() }
        val (c2s1, s2c1) = deriveSharedKeys(ephemeralSS)
        val (c2s2, s2c2) = deriveSharedKeys(ephemeralSS)
        assertContentEquals(c2s1, c2s2)
        assertContentEquals(s2c1, s2c2)
    }

    @Test
    fun deriveSharedKeys_differentInputProducesDifferentKeys() {
        val (c2s1, _) = deriveSharedKeys(ByteArray(64) { 1 })
        val (c2s2, _) = deriveSharedKeys(ByteArray(64) { 2 })
        assertFalse(c2s1.contentEquals(c2s2))
    }

    @Test
    fun deriveK2_isDeterministic_sameInput() {
        val ephemeralSS = ByteArray(64) { 1 }
        val serverSS = ByteArray(64) { 2 }
        val r1 = deriveK2(ephemeralSS, serverSS)
        val r2 = deriveK2(ephemeralSS, serverSS)
        assertContentEquals(r1.outputKeyingMaterial160, r2.outputKeyingMaterial160)
    }

    @Test
    fun deriveK2_differentInputProducesDifferentKeys() {
        val r1 = deriveK2(ByteArray(64) { 1 }, ByteArray(64) { 2 })
        val r2 = deriveK2(ByteArray(64) { 3 }, ByteArray(64) { 4 })
        assertFalse(r1.keyId.contentEquals(r2.keyId))
    }

    @Test
    fun deriveK2_keysAreNonZero_validInput() {
        val result = deriveK2(ByteArray(64) { 0xAA.toByte() }, ByteArray(64) { 0xBB.toByte() })
        assertFalse(result.clientToServerAppDataKey.all { it == 0.toByte() })
        assertFalse(result.serverToClientAppDataKey.all { it == 0.toByte() })
        assertFalse(result.keyId.all { it == 0.toByte() })
    }

    @Test
    fun buildM3InnerLayer_mlKemCiphertextPreserved_validInput() {
        val mlKemCt = ByteArray(32) { 0xCC.toByte() }
        val encaps = EncapsulationResult(
            serverSharedSecret = ByteArray(64),
            ecdhCiphertext = ByteArray(65).apply { this[0] = 0x04.toByte() },
            mlKemCiphertext = mlKemCt,
        )
        val result = buildM3InnerLayer(encaps)
        assertContentEquals(mlKemCt, result.mlKemCiphertext)
    }

    @Test
    fun encryptKeyConfirmation_differentKeysProduceDifferentOutput() {
        val hash = ByteArray(32) { 0x42.toByte() }
        val r1 = encryptKeyConfirmation(ByteArray(32) { 1 }, hash)
        val r2 = encryptKeyConfirmation(ByteArray(32) { 2 }, hash)
        assertFalse(r1.contentEquals(r2))
    }

    @Test
    fun encryptKeyConfirmation_differentHashesProduceDifferentOutput() {
        val key = ByteArray(32) { 0x42.toByte() }
        val r1 = encryptKeyConfirmation(key, ByteArray(32) { 1 })
        val r2 = encryptKeyConfirmation(key, ByteArray(32) { 2 })
        assertFalse(r1.contentEquals(r2))
    }

    @Test
    fun computeTranscriptHash_orderMatters_differentArrangement() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        val c = byteArrayOf(7, 8, 9)
        val r1 = computeTranscriptHash(a, b, c)
        val r2 = computeTranscriptHash(c, b, a)
        assertFalse(r1.contentEquals(r2))
    }

    @Test
    fun computeTranscriptHash_emptyArrays_returns32Bytes() {
        val result = computeTranscriptHash(ByteArray(0), ByteArray(0), ByteArray(0))
        assertEquals(32, result.size)
    }

    @Test
    fun computeTranscriptHash_isDeterministic_sameInput() {
        val m1 = byteArrayOf(1, 2, 3)
        val m2 = byteArrayOf(4, 5, 6)
        val m3 = byteArrayOf(7, 8, 9)
        assertContentEquals(
            computeTranscriptHash(m1, m2, m3),
            computeTranscriptHash(m1, m2, m3),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun encryptInnerLayer_canBeDecryptedBack_validInput() {
        val key = ByteArray(32) { 0x42.toByte() }
        val encaps = EncapsulationResult(
            serverSharedSecret = ByteArray(64),
            ecdhCiphertext = ByteArray(65).apply { this[0] = 0x04.toByte() },
            mlKemCiphertext = ByteArray(32) { 3 },
        )
        val inner = buildM3InnerLayer(encaps)
        val encrypted = encryptInnerLayer(key, inner)
        val decrypted = AesGcmCipherImpl().decrypt(key, encrypted)
        val restored = cbor.decodeFromByteArray(M3InnerLayer.serializer(), decrypted)
        assertEquals(inner.erpEnabled, restored.erpEnabled)
        assertEquals(inner.esoEnabled, restored.esoEnabled)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun encryptInnerLayer_differentKeyProducesDifferentOutput() {
        val inner = buildM3InnerLayer(
            EncapsulationResult(
                serverSharedSecret = ByteArray(64),
                ecdhCiphertext = ByteArray(65).apply { this[0] = 0x04.toByte() },
                mlKemCiphertext = ByteArray(32),
            ),
        )
        val r1 = encryptInnerLayer(ByteArray(32) { 1 }, inner)
        val r2 = encryptInnerLayer(ByteArray(32) { 2 }, inner)
        assertFalse(r1.contentEquals(r2))
    }

    @Test
    fun validateSignedVauPublicKeys_throwsWhenExpired() = runTest {
        val signed = buildSigned(
            vauKeys = defaultVauKeys(
                expiresAt = 999,
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = defaultValidationBundle(),
                clock = FixedClock(1000),
                requiredRoleOid = requiredOid,
            )
        }

        assertEquals("ASL keys expired: expiresAt=999, now=1000", error.message)
    }

    @Test
    fun validateSignedVauPublicKeys_throwsWhenCurveIsNotP256() = runTest {
        val signed = buildSigned(
            vauKeys = defaultVauKeys(
                ecdhPublicKey = defaultEcPublicKey(crv = "P-384"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = defaultValidationBundle(),
                clock = FixedClock(1000),
                requiredRoleOid = requiredOid,
            )
        }

        assertEquals("ECDH key must use P-256, got P-384", error.message)
    }

    @Test
    fun validateSignedVauPublicKeys_throwsWhenEcdhCoordinatesAreNot32Bytes() = runTest {
        val signed = buildSigned(
            vauKeys = defaultVauKeys(
                ecdhPublicKey = defaultEcPublicKey(
                    x = ByteArray(31),
                    y = ByteArray(32),
                ),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = defaultValidationBundle(),
                clock = FixedClock(1000),
                requiredRoleOid = requiredOid,
            )
        }

        assertEquals("ECDH coordinates must be 32 bytes each", error.message)
    }

    @Test
    fun validateSignedVauPublicKeys_throwsWhenMlKemKeySizeIsInvalid() = runTest {
        val signed = buildSigned(
            vauKeys = defaultVauKeys(
                mlKemPublicKey = ByteArray(1183),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = defaultValidationBundle(),
                clock = FixedClock(1000),
                requiredRoleOid = requiredOid,
            )
        }

        assertEquals("ML-KEM-768 key must be 1184 bytes, got 1183", error.message)
    }

    private fun defaultValidationBundle(
        certDataCache: AslStorage = AslStorageImpl(InMemoryStorage(), ResourceScope("", emptyList())),
        certDataFetcher: AslCertDataApi = { _, _ -> error("not reached in this test") },
    ): CertValidationBundle {
        val zetaClient =
            ZetaHttpClient(
                HttpClient(
                    MockEngine {
                        error("HTTP should not be called in this test")
                    },
                ),
            )

        val context = HttpContext(zetaClient, HttpRequestBuilder())
        val storage =
            RevocationChecker(RevocationStorage(InMemoryStorage(), ResourceScope("", listOf())), ZetaHttpClient(HttpClient {}), clock = SystemZetaClock)
        return CertValidationBundle(
            http = context,
            certDataFetcher = certDataFetcher,
            tiTrustAnchors = emptyList(),
            certDataCache = certDataCache,
            revocationChecker = storage,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildSigned(
        vauKeys: VauKeys = defaultVauKeys(),
    ): SignedVauPublicKeys {
        return SignedVauPublicKeys(
            signedPublicKeys = cbor.encodeToByteArray(VauKeys.serializer(), vauKeys),
            certificateHash = ByteArray(32) { 1 },
            certificateDescriptionVersion = 1,
            ocspResponse = ByteArray(0),
            es256Signature = ByteArray(64) { 2 },
        )
    }

    @Test
    fun validateVauKeyLifetime_doesNotThrow_whenKeyIsValidAndWithin30Days() {
        validateVauKeyLifetime(
            expiresAt = now + thirtyDays,
            issuedAt = now,
            nowEpochSeconds = now - day,
        )
    }

    @Test
    fun validateVauKeyLifetime_doesNotThrow_whenLifetimeIsExactly30Days() {
        validateVauKeyLifetime(
            expiresAt = now + thirtyDays,
            issuedAt = now,
            nowEpochSeconds = now,
        )
    }

    @Test
    fun validateVauKeyLifetime_throwsIllegalArgumentException_whenKeyIsExpired() {
        assertFailsWith<IllegalArgumentException> {
            validateVauKeyLifetime(
                expiresAt = now - 1,
                issuedAt = now - day,
                nowEpochSeconds = now,
            )
        }
    }

    @Test
    fun validateVauKeyLifetime_exceptionMessage_containsExpiresAt_whenExpired() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateVauKeyLifetime(expiresAt = now - 1, issuedAt = now - day, nowEpochSeconds = now)
        }
        assertEquals(ex.message?.contains("expired"), true)
    }

    @Test
    fun validateVauKeyLifetime_throwsIllegalArgumentException_whenLifetimeExceeds30Days() {
        assertFailsWith<IllegalArgumentException> {
            validateVauKeyLifetime(
                expiresAt = now + thirtyDays + 1,
                issuedAt = now,
                nowEpochSeconds = now - day,
            )
        }
    }

    @Test
    fun validateVauKeyLifetime_exceptionMessage_contains30Days_whenLifetimeTooLong() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateVauKeyLifetime(
                expiresAt = now + thirtyDays + 1,
                issuedAt = now,
                nowEpochSeconds = now - day,
            )
        }
        assertEquals(ex.message?.contains("30 days"), true)
    }

    @Test
    fun validateVauKeyLifetime_throwsIllegalArgumentException_whenExpiredAndLifetimeTooLong() {
        assertFailsWith<IllegalArgumentException> {
            validateVauKeyLifetime(
                expiresAt = now - 1,
                issuedAt = now - (thirtyDays + day),
                nowEpochSeconds = now,
            )
        }
    }

    @Test
    fun sanMatchesHost_returnsTrue_whenExactMatch() {
        assertTrue(sanMatchesHost("zeta-guard.example.com", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsTrue_whenExactMatchCaseInsensitive() {
        assertTrue(sanMatchesHost("ZETA-GUARD.EXAMPLE.COM", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsTrue_whenExactMatchMixedCase() {
        assertTrue(sanMatchesHost("Zeta-Guard.Example.Com", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenDifferentHost() {
        assertFalse(sanMatchesHost("other.example.com", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenSubdomainDiffers() {
        assertFalse(sanMatchesHost("api.example.com", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenDomainDiffers() {
        assertFalse(sanMatchesHost("zeta-guard.other.com", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsTrue_forWildcardMatchingSingleLabel() {
        assertTrue(sanMatchesHost("*.example.com", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsTrue_forWildcardCaseInsensitive() {
        assertTrue(sanMatchesHost("*.EXAMPLE.COM", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsTrue_forWildcardWithHyphenatedSubdomain() {
        assertTrue(sanMatchesHost("*.example.com", "zeta-guard-api.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenWildcardDoesNotMatchRootDomain() {
        assertFalse(sanMatchesHost("*.example.com", "example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenWildcardMatchesMultipleLabels() {
        assertFalse(sanMatchesHost("*.example.com", "a.b.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenWildcardSuffixDoesNotMatch() {
        assertFalse(sanMatchesHost("*.example.com", "zeta-guard.other.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenWildcardDoesNotMatchDifferentTld() {
        assertFalse(sanMatchesHost("*.example.com", "zeta-guard.example.de"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenNonWildcardSanAndHostDontMatch() {
        assertFalse(sanMatchesHost("example.com", "sub.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_forEmptySan() {
        assertFalse(sanMatchesHost("", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_forEmptyHost() {
        assertFalse(sanMatchesHost("*.example.com", ""))
    }

    @Test
    fun sanMatchesHost_returnsTrue_forEmptySanAndEmptyHost() {
        assertTrue(sanMatchesHost("", ""))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenWildcardOnlyAsterisk() {
        assertFalse(sanMatchesHost("*", "zeta-guard.example.com"))
    }

    @Test
    fun sanMatchesHost_returnsFalse_whenHostIsJustTheSuffix() {
        assertFalse(sanMatchesHost("*.example.com", ".example.com"))
    }

    @Test
    fun validateSignedVauPublicKeys_usesCachedCertData_whenAvailable() = runTest {
        // Arrange
        val cache = FakeCertDataCache(
            cached = buildCertData(),
        )

        val validation = defaultValidationBundle(
            certDataCache = cache,
            certDataFetcher = { _, _ -> error("Fetcher should not be called when cache hit") },
        )

        val signed = buildSigned()

        // Act
        assertFailsWith<Exception> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = validation,
                clock = FixedClock(1000),
                requiredRoleOid = requiredOid,
            )
        }

        // Assert
        assertEquals(1, cache.getCalls)
        assertEquals(0, cache.putCalls)
    }

    @Test
    fun validateSignedVauPublicKeys_fetchesAndStoresCertData_whenCacheMiss() = runTest {
        // Arrange
        val fetched = buildCertData()
        val cache = FakeCertDataCache()

        val validation = defaultValidationBundle(
            certDataCache = cache,
            certDataFetcher = { _, _ -> fetched },
        )

        val signed = buildSigned()

        // Act
        assertFailsWith<Exception> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = validation,
                clock = FixedClock(1000),
                requiredRoleOid = requiredOid,
            )
        }

        // Assert
        assertEquals(1, cache.getCalls)
        assertEquals(1, cache.putCalls)
        assertEquals(fetched.cert.toList(), cache.stored!!.cert.toList())
        assertEquals(fetched.ca.toList(), cache.stored!!.ca.toList())
        assertEquals(fetched.rcaChain.map { it.toList() }, cache.stored!!.rcaChain.map { it.toList() })
    }

    @Test
    fun validateSignedVauPublicKeys_usesCertificateHashAndVersionForCacheLookup() = runTest {
        // Arrange
        val cache = FakeCertDataCache(cached = buildCertData())

        val validation = defaultValidationBundle(
            certDataCache = cache,
            certDataFetcher = { _, _ -> error("Fetcher should not be called") },
        )

        val signed = buildSigned()

        // Act
        assertFailsWith<Exception> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = validation,
                clock = FixedClock(1000),
                requiredRoleOid = requiredOid,
            )
        }

        // Assert
        assertEquals(signed.certificateHash.toHexString(), cache.lastHash)
        assertEquals(signed.certificateDescriptionVersion, cache.lastVersion)
    }

    @Test
    fun decodeAndValidateVauKeys_usesSystemClock_byDefault() {
        val clock = SystemZetaClock
        val now = clock.now().epochSeconds
        val signed = buildSigned(
            vauKeys = defaultVauKeys(
                issuedAt = now - 60,
                expiresAt = now + 3600,
            ),
        )

        decodeAndValidateVauKeys(signed, clock)
    }

    @Test
    fun validateSignedVauPublicKeys_usesSystemClock_byDefault() = runTest {
        val now = Clock.System.now().epochSeconds

        val signed = buildSigned(
            vauKeys = defaultVauKeys(
                issuedAt = now - 60,
                expiresAt = now + 3600,
            ),
        )

        assertFailsWith<Exception> {
            validateSignedVauPublicKeys(
                signed = signed,
                validation = defaultValidationBundle(),
                requiredRoleOid = requiredOid,
                clock = SystemZetaClock,
            )
        }
    }
}

private fun defaultVauKeys(
    ecdhPublicKey: EcPointP256 = defaultEcPublicKey(),
    mlKemPublicKey: ByteArray = ByteArray(1184),
    expiresAt: Long = 2000,
    issuedAt: Long = 0,
): VauKeys {
    return VauKeys(
        ecdhPublicKey = ecdhPublicKey,
        mlKemPublicKey = mlKemPublicKey,
        expiresAt = expiresAt,
        issuedAt = issuedAt,
        comment = "",
    )
}

private fun buildCertData(): CertData =
    CertData(
        cert = byteArrayOf(1, 2, 3),
        ca = byteArrayOf(4, 5, 6),
        rcaChain = listOf(byteArrayOf(7, 8, 9)),
    )

private class FakeCertDataCache(
    private val cached: CertData? = null,
) : AslStorage {
    var getCalls = 0
    var putCalls = 0
    var lastHash: String? = null
    var lastVersion: Int? = null
    var stored: CertData? = null
    override suspend fun saveSession(session: EstablishedSession) {
        TODO("Not yet implemented")
    }

    override suspend fun getCurrentSession(): EstablishedSession? {
        TODO("Not yet implemented")
    }

    override suspend fun getCachedCertData(
        certificateHashHex: String,
        certificateDescriptionVersion: Int,
    ): CertData? {
        getCalls++
        lastHash = certificateHashHex
        lastVersion = certificateDescriptionVersion
        return cached
    }

    override suspend fun saveCachedCertData(
        certificateHashHex: String,
        certificateDescriptionVersion: Int,
        certData: CertData,
    ) {
        putCalls++
        lastHash = certificateHashHex
        lastVersion = certificateDescriptionVersion
        stored = certData
    }

    override suspend fun clear() {
        TODO("Not yet implemented")
    }
}

private fun defaultEcPublicKey(
    crv: String = "P-256",
    x: ByteArray = ByteArray(32),
    y: ByteArray = ByteArray(32),
): EcPointP256 {
    return EcPointP256(
        crv = crv,
        x = x,
        y = y,
    )
}

class FixedClock(
    private val epochSeconds: Long,
) : ZetaClock {
    override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
}
