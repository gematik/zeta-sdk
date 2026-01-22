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

import de.gematik.zeta.sdk.crypto.EcPointP256
import de.gematik.zeta.sdk.crypto.KeyPair
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class Message1(
    @SerialName("MessageType") val type: String,
    @SerialName("ECDH_PK") val ecdhPublicKey: EcPointP256,
    @SerialName("ML-KEM-768_PK") val mlKemPublicKey: ByteArray,
)

@Serializable
public data class Message2(
    @SerialName("MessageType") val type: String,
    @SerialName("ECDH_ct") val ecdhCiphertext: EcPointP256,
    @SerialName("ML-KEM-768_ct") val ml768Ciphertext: ByteArray,
    @SerialName("AEAD_ct") val aeadCiphertext: ByteArray? = null,
)

@Serializable
public data class Message3(
    @SerialName("MessageType") val type: String,
    @SerialName("AEAD_ct") val aeadCiphertext: ByteArray,
    @SerialName("AEAD_ct_key_confirmation") val aeadConfirmationCiphertext: ByteArray,
)

@Serializable
public data class Message4(
    @SerialName("MessageType") val type: String,
    @SerialName("AEAD_ct_key_confirmation") val aeadKeyConfirmationCiphertext: ByteArray,
)

@Serializable
public data class VauPairKeys(val ecdhKey: KeyPair, val ml768Key: KeyPair)

@Serializable
public data class Message1Result(
    val cid: String,
    val response: ByteArray,
    val transcript: ByteArray,
)

public data class Message1Bundle(
    val encoded: ByteArray,
    val keys: VauPairKeys,
)

@Serializable
public data class K2Keys(
    val outputKeyingMaterial160: ByteArray,
    val clientToServerConfirmationKey: ByteArray,
    val clientToServerAppDataKey: ByteArray,
    val serverToClientConfirmationKey: ByteArray,
    val serverToClientAppDataKey: ByteArray,
    val keyId: ByteArray,
)

public data class EncapsulationResult(
    val serverSharedSecret: ByteArray,
    val ecdhCiphertext: ByteArray,
    val mlKemCiphertext: ByteArray,
)

@Serializable
public data class AeadCipherText(
    val iv: ByteArray,
    val cipherText: ByteArray,
    val tag: ByteArray,
)

@Serializable
public data class SignedVauPublicKeys(
    @SerialName("signed_pub_keys") val signedPublicKeys: ByteArray,
    @SerialName("signature-ES256") val es256Signature: ByteArray,
    @SerialName("cert_hash") val certificateHash: ByteArray,
    @SerialName("cdv") val certificateDescriptionVersion: Int,
    @SerialName("ocsp_response") val ocspResponse: ByteArray,
)

@Serializable
public data class VauKeys(
    @SerialName("ECDH_PK") val ecdhPublicKey: EcPointP256,
    @SerialName("ML-KEM-768_PK") val mlKemPublicKey: ByteArray,
    @SerialName("iat") val issuedAt: Long,
    @SerialName("exp") val expiresAt: Long,
    @SerialName("comment") val comment: String,
)

public data class Message3Result(
    val m3Encoded: ByteArray,
    val k2: K2Keys,
    val expectedTranscriptHash: ByteArray,
)

@Serializable
public data class M3InnerLayer(
    @SerialName("ECDH_ct") val ecdhCiphertext: EcPointP256,
    @SerialName("ML-KEM-768_ct") val mlKemCiphertext: ByteArray,
    @SerialName("ERP") val erpEnabled: Boolean,
    @SerialName("ESO") val esoEnabled: Boolean,
)

@Serializable
public data class AslErrorMessage(
    @SerialName("MessageType") val messageType: String,
    @SerialName("ErrorCode") val errorCode: String,
    @SerialName("ErrorMessage") val errorMessage: String,
)
