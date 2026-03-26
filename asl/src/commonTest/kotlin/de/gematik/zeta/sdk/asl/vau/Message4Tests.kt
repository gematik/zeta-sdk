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

import de.gematik.zeta.sdk.asl.K2Keys
import de.gematik.zeta.sdk.asl.Message3Result
import de.gematik.zeta.sdk.asl.Message4
import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import kotlin.test.Test
import kotlin.test.assertFailsWith

class Message4Tests {
    @Test
    fun validateMessage4AndFinalizeSession_succeeds_validHashMatch() {
        // Arrange
        val s2cConfKey = ByteArray(32) { 0x42.toByte() }
        val transcriptHash = ByteArray(32) { 0x99.toByte() }
        val cipher = AesGcmCipherImpl()
        val encryptedHash = cipher.encrypt(s2cConfKey, transcriptHash)

        val k2 = buildK2Keys(s2cConfirmationKey = s2cConfKey)
        val m3Result = buildMessage3Result(k2 = k2)
        val m4 = buildMessage4(aeadKeyConfirmationCiphertext = encryptedHash)

        // Act & Assert
        validateMessage4AndFinalizeSession(m4, m3Result, transcriptHash)
    }

    @Test
    fun validateMessage4AndFinalizeSession_succeeds_emptyHash() {
        // Arrange
        val s2cConfKey = ByteArray(32) { 0x11.toByte() }
        val transcriptHash = ByteArray(0)
        val cipher = AesGcmCipherImpl()
        val encryptedHash = cipher.encrypt(s2cConfKey, transcriptHash)

        val k2 = buildK2Keys(s2cConfirmationKey = s2cConfKey)
        val m3Result = buildMessage3Result(k2 = k2)
        val m4 = buildMessage4(aeadKeyConfirmationCiphertext = encryptedHash)

        // Act & Assert (should not throw)
        validateMessage4AndFinalizeSession(m4, m3Result, transcriptHash)
    }

    @Test
    fun validateMessage4AndFinalizeSession_throwsException_wrongType() {
        // Arrange
        val m3Result = buildMessage3Result()
        val m4 = buildMessage4(type = "M3")
        val transcriptHash = ByteArray(32)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            validateMessage4AndFinalizeSession(m4, m3Result, transcriptHash)
        }
    }

    @Test
    fun validateMessage4AndFinalizeSession_throwsException_emptyType() {
        // Arrange
        val m3Result = buildMessage3Result()
        val m4 = buildMessage4(type = "")
        val transcriptHash = ByteArray(32)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            validateMessage4AndFinalizeSession(m4, m3Result, transcriptHash)
        }
    }

    @Test
    fun validateMessage4AndFinalizeSession_throwsException_hashMismatch() {
        // Arrange
        val s2cConfKey = ByteArray(32) { 0x42.toByte() }
        val correctHash = ByteArray(32) { 0x99.toByte() }
        val wrongHash = ByteArray(32) { 0xAA.toByte() }
        val cipher = AesGcmCipherImpl()
        val encryptedHash = cipher.encrypt(s2cConfKey, correctHash)

        val k2 = buildK2Keys(s2cConfirmationKey = s2cConfKey)
        val m3Result = buildMessage3Result(k2 = k2)
        val m4 = buildMessage4(aeadKeyConfirmationCiphertext = encryptedHash)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            validateMessage4AndFinalizeSession(m4, m3Result, wrongHash)
        }
    }

    @Test
    fun validateMessage4AndFinalizeSession_throwsException_differentHashLength() {
        // Arrange
        val s2cConfKey = ByteArray(32) { 0x42.toByte() }
        val serverHash = ByteArray(32) { 0x99.toByte() }
        val clientHash = ByteArray(16) { 0x99.toByte() }
        val cipher = AesGcmCipherImpl()
        val encryptedHash = cipher.encrypt(s2cConfKey, serverHash)

        val k2 = buildK2Keys(s2cConfirmationKey = s2cConfKey)
        val m3Result = buildMessage3Result(k2 = k2)
        val m4 = buildMessage4(aeadKeyConfirmationCiphertext = encryptedHash)

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            validateMessage4AndFinalizeSession(m4, m3Result, clientHash)
        }
    }
    private fun buildK2Keys(
        s2cConfirmationKey: ByteArray = ByteArray(32) { 1 },
    ): K2Keys = K2Keys(
        outputKeyingMaterial160 = ByteArray(160),
        clientToServerConfirmationKey = ByteArray(32),
        clientToServerAppDataKey = ByteArray(32),
        serverToClientConfirmationKey = s2cConfirmationKey,
        serverToClientAppDataKey = ByteArray(32),
        keyId = ByteArray(32),
    )

    private fun buildMessage3Result(
        k2: K2Keys = buildK2Keys(),
    ): Message3Result = Message3Result(
        m3Encoded = ByteArray(0),
        k2 = k2,
        expectedTranscriptHash = ByteArray(32),
    )

    private fun buildMessage4(
        type: String = "M4",
        aeadKeyConfirmationCiphertext: ByteArray = ByteArray(0),
    ): Message4 = Message4(
        type = type,
        aeadKeyConfirmationCiphertext = aeadKeyConfirmationCiphertext,
    )
}
