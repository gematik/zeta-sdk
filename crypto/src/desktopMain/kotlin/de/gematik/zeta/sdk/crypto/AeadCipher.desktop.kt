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

@file:OptIn(DelicateCryptographyApi::class)

package de.gematik.zeta.sdk.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import secureRandom

actual class AesGcmCipher actual constructor() {

    private val TAG_BITS = 128
    private val IV_LEN = 12
    private val AES_KEY_LEN = 32

    private val provider = CryptographyProvider.Default
    private val aesGcm = provider.get(AES.GCM)

    actual fun encrypt(
        aesKey: ByteArray,
        plainText: ByteArray,
        iv: ByteArray?,
        aad: ByteArray?,
    ): ByteArray {
        require(aesKey.size == AES_KEY_LEN) { "Key must be 32 bytes" }

        val useIv = iv ?: newIv()
        require(useIv.size == IV_LEN) { "GCM must be $IV_LEN bytes" }

        val key: AES.GCM.Key = aesGcm.keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, aesKey)

        val cipherTextWithTag = key.cipher().encryptWithIvBlocking(
            plaintext = plainText,
            iv = useIv,
            associatedData = aad,
        )

        return useIv + cipherTextWithTag
    }

    actual fun decrypt(
        aesKey: ByteArray,
        cipherText: ByteArray,
        iv: ByteArray?,
        aad: ByteArray?,
    ): ByteArray {
        require(aesKey.size == AES_KEY_LEN) { "Key must be 32 bytes" }
        require(cipherText.size > IV_LEN) { "Ciphertext too short" }

        val key: AES.GCM.Key = aesGcm.keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, aesKey)

        val nonce = iv ?: cipherText.copyOfRange(0, IV_LEN)
        val body =
            if (iv != null) {
                cipherText
            } else {
                cipherText.copyOfRange(IV_LEN, cipherText.size)
            }

        return key.cipher(TAG_BITS.bits).decryptWithIvBlocking(
            ciphertext = body,
            iv = nonce,
            associatedData = aad,
        )
    }

    private fun newIv(): ByteArray =
        ByteArray(IV_LEN).also { secureRandom(it) }
}
