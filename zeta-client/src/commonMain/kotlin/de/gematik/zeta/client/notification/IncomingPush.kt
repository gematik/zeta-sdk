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

package de.gematik.zeta.client.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Payload key of the encrypted message, as sent by the push gateway for FCM and APNs alike. */
internal const val CIPHERTEXT_KEY = "ciphertext"

/** Payload key of the month tag that selects the decryption key. */
internal const val TIME_MESSAGE_ENCRYPTED_KEY = "time_message_encrypted"

private const val MESSAGE_KEY = "message"

/**
 * Turns the decrypted payload JSON from the notification service into the text for the notification
 * banner: its `message` field when present, otherwise the decrypted text as is.
 */
internal fun pushDisplayText(decrypted: String): String {
    val payload = runCatching { Json.parseToJsonElement(decrypted) }.getOrNull() as? JsonObject
        ?: return decrypted
    val message = payload[MESSAGE_KEY] as? JsonPrimitive ?: return decrypted
    return if (message.isString) message.content else decrypted
}
