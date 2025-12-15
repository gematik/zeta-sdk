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

package de.gematik.zeta.sdk.crypto

private const val DEFAULT_NONCE_LEN = 12
private const val DEFAULT_TAG_LEN = 16

expect class AesGcmCipher() {
    fun encrypt(aesKey: ByteArray, plainText: ByteArray, iv: ByteArray? = null, aad: ByteArray? = null): ByteArray
    fun decrypt(aesKey: ByteArray, cipherText: ByteArray, iv: ByteArray? = null, aad: ByteArray? = null): ByteArray
}

data class AeadParts(
    val iv: ByteArray,
    val cipherText: ByteArray,
    val tag: ByteArray,
) {
    init {
        require(iv.isNotEmpty()) { "IV must not be empty" }
        require(tag.isNotEmpty()) { "TAG must not be empty" }
        require(cipherText.isNotEmpty()) { "Ciphertext must not be empty" }
    }

    /** Pack as [IV || CIPHERTEXT || TAG] */
    fun pack(): ByteArray {
        val out = ByteArray(iv.size + cipherText.size + tag.size)
        iv.copyInto(out, 0)
        cipherText.copyInto(out, iv.size)
        tag.copyInto(out, iv.size + cipherText.size)
        return out
    }
}

fun ByteArray.unpackAead(
    nonceLen: Int = DEFAULT_NONCE_LEN,
    tagLen: Int = DEFAULT_TAG_LEN,
): AeadParts {
    require(this.size > nonceLen + tagLen) { "AEAD blob too short" }
    val iv = this.copyOfRange(0, nonceLen)
    val tagStart = this.size - tagLen
    val ct = this.copyOfRange(nonceLen, tagStart)
    val tag = this.copyOfRange(tagStart, this.size)
    return AeadParts(iv, ct, tag)
}
