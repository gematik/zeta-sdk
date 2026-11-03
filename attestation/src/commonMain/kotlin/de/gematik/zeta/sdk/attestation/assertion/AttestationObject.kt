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
import de.gematik.zeta.sdk.attestation.model.AppleAttestationAuthData
import de.gematik.zeta.sdk.attestation.model.AppleAttestationStatement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlin.io.encoding.Base64

private const val RP_ID_HASH_LEN = 32
private const val FLAGS_LEN = 1
private const val COUNTER_LEN = 4
private const val AAGUID_LEN = 16
private const val CRED_ID_LEN_LEN = 2
private const val FIXED_LEN = RP_ID_HASH_LEN + FLAGS_LEN + COUNTER_LEN + AAGUID_LEN + CRED_ID_LEN_LEN
private const val BYTE_MASK = 0xFFL
private const val HEX_RADIX = 16

/** Apple App Attest attestation object as returned by `DCAppAttestService.attestKey` (CBOR map). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class AppAttestStatement(val x5c: List<ByteArray>, @ByteString val receipt: ByteArray)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class AttestationObject(val fmt: String, val attStmt: AppAttestStatement, @ByteString val authData: ByteArray)

class DecodedAttestationObject(
    val x5c: List<ByteArray>,
    val receipt: ByteArray,
    val rpIdHash: ByteArray,
    val flags: Int,
    val counter: Long,
    val aaguid: String,
    val credentialId: ByteArray,
) {
    fun statement() = AppleAttestationStatement(x5c.map { Base64.encode(it) }, Base64.encode(receipt))

    fun authData() = AppleAttestationAuthData(
        rpidHash = Base64.encode(rpIdHash),
        flags = "0x" + flags.toString(HEX_RADIX).padStart(2, '0'),
        counter = counter,
        aaguid = aaguid,
        credentialId = Base64.encode(credentialId),
    )
}

@OptIn(ExperimentalSerializationApi::class)
private val cbor = Cbor {
    ignoreUnknownKeys = true
    alwaysUseByteString = true
}

@OptIn(ExperimentalSerializationApi::class)
fun decodeAttestationObject(bytes: ByteArray): DecodedAttestationObject {
    val obj = cbor.decodeFromByteArray(AttestationObject.serializer(), bytes)
    require(obj.fmt == APPLE_APP_ATTEST_FMT) { "attestation object: unexpected fmt '${obj.fmt}'" }
    val ad = obj.authData
    require(ad.size >= FIXED_LEN) { "attestation object: authData too short (${ad.size})" }
    var offset = RP_ID_HASH_LEN
    val flags = ad[offset].toInt() and BYTE_MASK.toInt()
    offset += FLAGS_LEN
    var counter = 0L
    repeat(COUNTER_LEN) { counter = (counter shl 8) or (ad[offset + it].toLong() and BYTE_MASK) }
    offset += COUNTER_LEN
    val aaguid = ad.copyOfRange(offset, offset + AAGUID_LEN).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
    offset += AAGUID_LEN
    val credIdLen = ((ad[offset].toInt() and BYTE_MASK.toInt()) shl 8) or (ad[offset + 1].toInt() and BYTE_MASK.toInt())
    offset += CRED_ID_LEN_LEN
    require(ad.size >= offset + credIdLen) { "attestation object: credentialId truncated" }
    return DecodedAttestationObject(
        x5c = obj.attStmt.x5c,
        receipt = obj.attStmt.receipt,
        rpIdHash = ad.copyOfRange(0, RP_ID_HASH_LEN),
        flags = flags,
        counter = counter,
        aaguid = aaguid,
        credentialId = ad.copyOfRange(offset, offset + credIdLen),
    )
}
