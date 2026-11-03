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

import de.gematik.zeta.sdk.attestation.model.APPLE_APP_ATTEST_FMT
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalSerializationApi::class)
class AttestationObjectTest {
    private val cbor = Cbor { alwaysUseByteString = true }
    private val rpIdHash = ByteArray(32) { it.toByte() }
    private val credentialId = ByteArray(32) { 0x42 }
    private val leaf = byteArrayOf(0x30, 0x82.toByte(), 0x01)
    private val intermediate = byteArrayOf(0x30, 0x82.toByte(), 0x02)
    private val receipt = byteArrayOf(0x30, 0x80.toByte(), 0x06)

    private fun authData(counter: ByteArray = byteArrayOf(0, 0, 0, 0), aaguid: String = "appattestdevelop", credId: ByteArray = credentialId) =
        rpIdHash + byteArrayOf(0x40) + counter + aaguid.encodeToByteArray().copyOf(16) +
            byteArrayOf((credId.size shr 8).toByte(), credId.size.toByte()) + credId + byteArrayOf(0xA5.toByte(), 0x01)

    private fun encode(authData: ByteArray, fmt: String = APPLE_APP_ATTEST_FMT) = cbor.encodeToByteArray(
        AttestationObject.serializer(),
        AttestationObject(fmt, AppAttestStatement(listOf(leaf, intermediate), receipt), authData),
    )

    @Test
    fun decode_extractsStatementAndAuthData() {
        val decoded = decodeAttestationObject(encode(authData(counter = byteArrayOf(0, 0, 1, 2))))

        assertEquals(2, decoded.x5c.size)
        assertContentEquals(leaf, decoded.x5c[0])
        assertContentEquals(receipt, decoded.receipt)
        assertContentEquals(rpIdHash, decoded.rpIdHash)
        assertEquals(0x40, decoded.flags)
        assertEquals(258L, decoded.counter)
        assertEquals("appattestdevelop", decoded.aaguid)
        assertContentEquals(credentialId, decoded.credentialId)
    }

    @Test
    fun postureFields_areBase64_hexFlags_andTrimmedAaguid() {
        val decoded = decodeAttestationObject(encode(authData(aaguid = "appattest")))

        val statement = decoded.statement()
        assertEquals(listOf(Base64.encode(leaf), Base64.encode(intermediate)), statement.x5c)
        assertEquals(Base64.encode(receipt), statement.receipt)
        val authData = decoded.authData()
        assertEquals(Base64.encode(rpIdHash), authData.rpidHash)
        assertEquals("0x40", authData.flags)
        assertEquals(0L, authData.counter)
        assertEquals("appattest", authData.aaguid)
        assertEquals(Base64.encode(credentialId), authData.credentialId)
    }

    @Test
    fun decode_rejectsForeignFmt() {
        assertFailsWith<IllegalArgumentException> { decodeAttestationObject(encode(authData(), fmt = "packed")) }
    }

    @Test
    fun decode_rejectsShortAuthData() {
        assertFailsWith<IllegalArgumentException> { decodeAttestationObject(encode(rpIdHash + byteArrayOf(0x40))) }
    }

    @Test
    fun decode_rejectsTruncatedCredentialId() {
        val truncated = rpIdHash + byteArrayOf(0x40) + ByteArray(4) + ByteArray(16) + byteArrayOf(0x00, 0x20) + ByteArray(5)
        assertFailsWith<IllegalArgumentException> { decodeAttestationObject(encode(truncated)) }
    }
}
