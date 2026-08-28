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

package de.gematik.zeta.sdk.notifications

/** Notification Service scopes per the normative scope table (A_29979). */
object NotificationScopes {
    const val PUSHER_READ = "notification.pusher.read"
    const val PUSHER_WRITE = "notification.pusher.write"
    const val CHANNEL_READ = "notification.channel.read"
    const val CHANNEL_WRITE = "notification.channel.write"

    /** History is an optional NS feature; the scope appears in `scopes_supported` only when enabled. */
    const val HISTORY_READ = "notification.history.read"

    /** The scopes required by the operations [NotificationApiClient] currently exposes. */
    val ALL: Set<String> = setOf(PUSHER_READ, PUSHER_WRITE, CHANNEL_READ, CHANNEL_WRITE)
}
