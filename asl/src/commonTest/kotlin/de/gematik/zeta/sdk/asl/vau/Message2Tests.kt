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

import de.gematik.zeta.sdk.asl.EncapsulationResult
import de.gematik.zeta.sdk.asl.Message2
import de.gematik.zeta.sdk.asl.cbor
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import de.gematik.zeta.sdk.crypto.EcPointP256
import de.gematik.zeta.sdk.crypto.hashWithSha256
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Message2And3Test {

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
}
