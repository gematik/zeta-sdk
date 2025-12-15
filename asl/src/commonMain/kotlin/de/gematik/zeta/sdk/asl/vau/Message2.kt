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
import de.gematik.zeta.sdk.asl.K2Keys
import de.gematik.zeta.sdk.asl.M3InnerLayer
import de.gematik.zeta.sdk.asl.Message1Bundle
import de.gematik.zeta.sdk.asl.Message1Result
import de.gematik.zeta.sdk.asl.Message2
import de.gematik.zeta.sdk.asl.Message3Result
import de.gematik.zeta.sdk.asl.SignedVauPublicKeys
import de.gematik.zeta.sdk.asl.VauKeys
import de.gematik.zeta.sdk.asl.VauPairKeys
import de.gematik.zeta.sdk.asl.cbor
import de.gematik.zeta.sdk.crypto.AesGcmCipher
import de.gematik.zeta.sdk.crypto.EcPointP256
import de.gematik.zeta.sdk.crypto.Hkdf
import de.gematik.zeta.sdk.crypto.Kem
import de.gematik.zeta.sdk.crypto.hashWithSha256
import kotlinx.serialization.ExperimentalSerializationApi

@Suppress("UnsafeCallOnNullableType")
@OptIn(ExperimentalSerializationApi::class)
internal fun processMessage2AndDeriveMessage3(
    message1: Message1Bundle,
    resultMessage1: Message1Result,
    mlKem: Kem,
    ecdhKem: Kem,
): Message3Result {
    val message2 = parseMessage2(resultMessage1.response)
    val ssE = deriveSharedSecret(mlKem, ecdhKem, message1.keys, message2)
    val (k1_c2s, k1_s2c) = deriveSharedKeys(ssE)
    val signed = decryptSignedKeys(k1_s2c, message2.aeadCiphertext!!)
    val encaps = encapsulateForServer(mlKem, ecdhKem, signed)
    val inner = buildM3InnerLayer(encaps)

    val innerCipherText = encryptInnerLayer(k1_c2s, inner)

    val transcriptHash = computeTranscriptHash(
        m1 = message1.encoded,
        m2 = resultMessage1.response,
        m3 = innerCipherText,
    )
    val k2 = deriveK2(ssE, encaps.serverSharedSecret)

    return Message3Result(innerCipherText, k2, transcriptHash)
}

@OptIn(ExperimentalSerializationApi::class)
public fun parseMessage2(raw: ByteArray): Message2 {
    val m2 = cbor.decodeFromByteArray(Message2.serializer(), raw)
    require(m2.type == "M2") { "Wrong MessageType" }

    return m2
}

public fun EcPointP256.toUncompressedPoint(): ByteArray {
    require(crv == "P-256")
    require(x.size == 32 && y.size == 32)

    return byteArrayOf(0x04) + x + y
}

public fun EcPointP256.Companion.fromKemBytes(bytes: ByteArray): EcPointP256 {
    require(bytes.size == 65)
    val x = bytes.copyOfRange(1, 33)
    val y = bytes.copyOfRange(33, 65)
    return EcPointP256(x = x, y = y, crv = "P-256")
}

public fun deriveSharedSecret(ml768Kem: Kem, ecdhKem: Kem, keys: VauPairKeys, message2: Message2): ByteArray {
    val ss_e_ec = ecdhKem.decapsulate(keys.ecdhKey.privateKey, message2.ecdhCiphertext.toUncompressedPoint())
    val ss_e_ml = ml768Kem.decapsulate(keys.ml768Key.privateKey, message2.ml768Ciphertext)

    return ss_e_ec + ss_e_ml
}

public fun deriveSharedKeys(ephemeralSharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
    val k1All = Hkdf.hkdfSha256(
        inputKeyMaterial = ephemeralSharedSecret,
        salt = ByteArray(32) { 0 },
        info = ByteArray(0),
        outLen = 64,
    )
    val k1_c2s = k1All.copyOfRange(0, 32)
    val k1_s2c = k1All.copyOfRange(32, 64)

    return k1_c2s to k1_s2c
}

@OptIn(ExperimentalSerializationApi::class)
public fun decryptSignedKeys(k1S2c: ByteArray, signedKeysCt: ByteArray): SignedVauPublicKeys {
    val plain = AesGcmCipher().decrypt(k1S2c, signedKeysCt)
    return cbor.decodeFromByteArray(SignedVauPublicKeys.serializer(), plain)
}

@OptIn(ExperimentalSerializationApi::class)
public fun encapsulateForServer(mlKem: Kem, ecdhKem: Kem, signed: SignedVauPublicKeys): EncapsulationResult {
    val vauKeys = cbor.decodeFromByteArray(VauKeys.serializer(), signed.signedPublicKeys)
    val serverEcdhPubBytes = vauKeys.ecdhPublicKey
    val ecdhKemResult = ecdhKem.encapsulate(serverEcdhPubBytes.toUncompressedPoint())
    val mlKemResult = mlKem.encapsulate(vauKeys.mlKemPublicKey)

    return EncapsulationResult(
        serverSharedSecret = ecdhKemResult.sharedSecret + mlKemResult.sharedSecret,
        ecdhCiphertext = ecdhKemResult.ciphertext,
        mlKemCiphertext = mlKemResult.ciphertext,
    )
}

public fun deriveK2(ephemeralSharedSecret: ByteArray, serverSharedSecret: ByteArray): K2Keys {
    val ss = ephemeralSharedSecret + serverSharedSecret

    val okm = Hkdf.hkdfSha256(
        inputKeyMaterial = ss,
        salt = ByteArray(32) { 0 },
        info = ByteArray(0),
        outLen = 160,
    )

    return K2Keys(
        outputKeyingMaterial160 = okm,
        clientToServerConfirmationKey = okm.copyOfRange(0, 32),
        clientToServerAppDataKey = okm.copyOfRange(32, 64),
        serverToClientConfirmationKey = okm.copyOfRange(64, 96),
        serverToClientAppDataKey = okm.copyOfRange(96, 128),
        keyId = okm.copyOfRange(128, 160),
    )
}

public fun buildM3InnerLayer(encaps: EncapsulationResult): M3InnerLayer =
    M3InnerLayer(
        ecdhCiphertext = EcPointP256.fromKemBytes(encaps.ecdhCiphertext),
        mlKemCiphertext = encaps.mlKemCiphertext,
        erpEnabled = false,
        esoEnabled = false,
    )

@OptIn(ExperimentalSerializationApi::class)
public fun encryptInnerLayer(k1_c2s: ByteArray, inner: M3InnerLayer): ByteArray {
    val innerCbor = cbor.encodeToByteArray(M3InnerLayer.serializer(), inner)
    return AesGcmCipher().encrypt(k1_c2s, innerCbor)
}

@OptIn(ExperimentalSerializationApi::class)
public fun computeTranscriptHash(m1: ByteArray, m2: ByteArray, m3: ByteArray): ByteArray {
    val transcript = m1 + m2 + m3
    return hashWithSha256(transcript)
}

public fun encryptKeyConfirmation(clientToServerConfirmationKey: ByteArray, transcriptHash: ByteArray): ByteArray =
    AesGcmCipher().encrypt(clientToServerConfirmationKey, transcriptHash)
