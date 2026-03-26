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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import de.gematik.zeta.sdk.crypto.unpackAead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EstablishedSessionTests {
    private val keyId = ByteArray(32) { 0x01 }
    private val c2sKey = ByteArray(32) { 0x02 }
    private val s2cKey = ByteArray(32) { 0x03 }
    private val cipher = AesGcmCipherImpl()
    private val testPlaintext = "hello asl".encodeToByteArray()

    @Test
    fun init_throwsException_keyIdNot32Bytes() {
        // Arrange
        val invalidKeyId = ByteArray(16) { 0x01 }

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            buildSession(keyId = invalidKeyId)
        }
    }

    @Test
    fun init_throwsException_c2sKeyNot32Bytes() {
        // Arrange
        val invalidC2sKey = ByteArray(16) { 0x02 }

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            buildSession(c2sKey = invalidC2sKey)
        }
    }

    @Test
    fun init_throwsException_s2cKeyNot32Bytes() {
        // Arrange
        val invalidS2cKey = ByteArray(16) { 0x03 }

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            buildSession(s2cKey = invalidS2cKey)
        }
    }

    @Test
    fun encryptRequest_returnsAtLeast72Bytes_validInput() {
        // Arrange
        val session = buildSession()

        // Act
        val result = session.encryptRequest(testPlaintext)

        // Assert
        assertTrue(result.size >= 72)
    }

    @Test
    fun encryptRequest_incrementsRequestCounterToOne_calledOnce() {
        // Arrange
        val session = buildSession(requestCounter = 0L)

        // Act
        session.encryptRequest(testPlaintext)

        // Assert
        assertEquals(1L, session.requestCounter)
    }

    @Test
    fun encryptRequest_incrementsRequestCounterToTwo_calledTwice() {
        // Arrange
        val session = buildSession(requestCounter = 0L)

        // Act
        session.encryptRequest(testPlaintext)
        session.encryptRequest(testPlaintext)

        // Assert
        assertEquals(2L, session.requestCounter)
    }

    @Test
    fun encryptRequest_incrementsEncCounter_calledOnce() {
        // Arrange
        val session = buildSession()

        // Act
        session.encryptRequest(testPlaintext)

        // Assert
        assertEquals(1L, session.encCounter)
    }

    @Test
    fun encryptRequest_incrementsEncCounterToTwo_calledTwice() {
        // Arrange
        val session = buildSession()

        // Act
        session.encryptRequest(testPlaintext)
        session.encryptRequest(testPlaintext)

        // Assert
        assertEquals(2L, session.encCounter)
    }

    @Test
    fun decryptResponse_throwsException_extendedTooShort() {
        // Arrange
        val session = buildSession(requestCounter = 1L)
        val tooShort = ByteArray(MIN_EXT_LEN - 1)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            session.decryptResponse(tooShort)
        }
    }

    @Test
    fun decryptResponse_throwsException_puMismatch() {
        // Arrange
        val session = buildSession(pu = Environment.Production, requestCounter = 1L)
        val blob = buildValidResponseBlob(session, testPlaintext)
        blob[PU_OFFSET] = 0.toByte()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            session.decryptResponse(blob)
        }
    }

    @Test
    fun decryptResponse_throwsException_kindNotResponse() {
        // Arrange
        val session = buildSession(requestCounter = 1L)
        val blob = buildValidResponseBlob(session, testPlaintext)
        blob[KIND_OFFSET] = Kind.Request.v

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            session.decryptResponse(blob)
        }
    }

    @Test
    fun decryptResponse_throwsException_counterMismatch() {
        // Arrange
        val session = buildSession(requestCounter = 1L)
        val blob = buildValidResponseBlob(session, testPlaintext)
        blob[CTR_OFFSET + 7] = (session.requestCounter + 1).toByte()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            session.decryptResponse(blob)
        }
    }

    @Test
    fun decryptResponse_throwsException_keyIdMismatch() {
        // Arrange
        val session = buildSession(requestCounter = 1L)
        val blob = buildValidResponseBlob(session, testPlaintext)
        blob[KEYID_OFFSET] = 0xFF.toByte()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            session.decryptResponse(blob)
        }
    }

    @Test
    fun decryptResponse_returnsOriginalPlaintext_validRoundtrip() {
        // Arrange
        val session = buildSession(requestCounter = 1L)
        val blob = buildValidResponseBlob(session, testPlaintext)

        // Act
        val result = session.decryptResponse(blob)

        // Assert
        assertEquals(testPlaintext.toList(), result.toList())
    }

    @Test
    fun zetaHeaderToBytes_throwsIllegalArgumentException_keyIdNot32Bytes() {
        // Arrange
        val invalidKeyId = ByteArray(16) { 0x01 }
        val header = ZetaHeader(
            pu = Environment.Production,
            kind = Kind.Response,
            counter = 1L,
            keyId = invalidKeyId,
        )

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            header.toBytes()
        }
    }

    @Test
    fun zetaHeaderFrom_throwsIllegalArgumentException_dataTooShort() {
        // Arrange
        val tooShort = ByteArray(HEADER_LEN - 1)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            ZetaHeader.from(tooShort)
        }
    }

    @Test
    fun zetaHeaderFrom_returnsCorrectHeader_validBytes() {
        // Arrange
        val original = ZetaHeader(
            pu = Environment.Testing,
            kind = Kind.Request,
            counter = 42L,
            keyId = keyId,
        )
        val bytes = original.toBytes()

        // Act
        val parsed = ZetaHeader.from(bytes)

        // Assert
        assertEquals(original.pu, parsed.pu)
        assertEquals(original.kind, parsed.kind)
        assertEquals(original.counter, parsed.counter)
        assertEquals(original.keyId.toList(), parsed.keyId.toList())
    }

    private fun buildSession(
        keyId: ByteArray = this.keyId,
        c2sKey: ByteArray = this.c2sKey,
        s2cKey: ByteArray = this.s2cKey,
        cid: String? = "/session/abc123",
        pu: Environment = Environment.Production,
        requestCounter: Long = 0L,
    ): EstablishedSession =
        EstablishedSession(
            keyId = keyId,
            c2sAppDataKey = c2sKey,
            s2cAppDataKey = s2cKey,
            cid = cid,
            pu = pu,
            requestCounter = requestCounter,
        )

    private fun buildValidResponseBlob(session: EstablishedSession, plaintext: ByteArray): ByteArray {
        val header = ZetaHeader(
            pu = session.pu,
            kind = Kind.Response,
            counter = session.requestCounter,
            keyId = session.keyId,
        ).toBytes()

        val iv = ByteArray(IV_LEN) { 0xAA.toByte() }
        val blob = cipher.encrypt(session.s2cAppDataKey, plaintext, iv, header)
        val parts = blob.unpackAead()

        return header + parts.iv + parts.cipherText + parts.tag
    }
}
