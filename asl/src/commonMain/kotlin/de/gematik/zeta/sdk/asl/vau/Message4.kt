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
import de.gematik.zeta.sdk.asl.Message3Result
import de.gematik.zeta.sdk.asl.Message4
import de.gematik.zeta.sdk.crypto.AesGcmCipher

internal fun validateMessage4AndFinalizeSession(m4: Message4, message3result: Message3Result, transcriptHash: ByteArray) {
    require(m4.type == "M4") { "Unexpected MessageType: ${m4.type}" }

    val serverHash = AesGcmCipher()
        .decrypt(message3result.k2.serverToClientConfirmationKey, m4.aeadKeyConfirmationCiphertext)

    if (!serverHash.contentEquals(transcriptHash)) {
        error("Server transcript hash mismatch")
    }
}
