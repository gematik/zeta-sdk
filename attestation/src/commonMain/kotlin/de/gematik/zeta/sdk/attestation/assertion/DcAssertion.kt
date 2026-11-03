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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor

private const val RP_ID_HASH_LEN = 32
private const val FLAGS_LEN = 1
private const val COUNTER_LEN = 4
private const val AUTH_DATA_MIN_LEN = RP_ID_HASH_LEN + FLAGS_LEN + COUNTER_LEN
private const val BYTE_MASK = 0xFFL

/** Apple App Attest assertion object as returned by `DCAppAttestService.generateAssertion` (CBOR map). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class DcAssertion(
    @ByteString val signature: ByteArray,
    @ByteString val authenticatorData: ByteArray,
)

class DecodedAssertion(val signature: ByteArray, val rpIdHash: ByteArray, val counter: Long)

@OptIn(ExperimentalSerializationApi::class)
private val cbor = Cbor { ignoreUnknownKeys = true }

/**
 * Decode `dc_assertion`: `authenticatorData = rpIdHash[32] ‖ flags[1] ‖ signCount[4]` (big-endian).
 * @throws IllegalArgumentException when the authenticator data is too short; serialization errors on malformed CBOR.
 */
@OptIn(ExperimentalSerializationApi::class)
fun decodeDcAssertion(bytes: ByteArray): DecodedAssertion {
    val assertion = cbor.decodeFromByteArray(DcAssertion.serializer(), bytes)
    val authData = assertion.authenticatorData
    require(authData.size >= AUTH_DATA_MIN_LEN) { "dc_assertion: authenticatorData too short (${authData.size})" }
    val counterStart = RP_ID_HASH_LEN + FLAGS_LEN
    var counter = 0L
    repeat(COUNTER_LEN) { counter = (counter shl 8) or (authData[counterStart + it].toLong() and BYTE_MASK) }
    return DecodedAssertion(assertion.signature, authData.copyOfRange(0, RP_ID_HASH_LEN), counter)
}
