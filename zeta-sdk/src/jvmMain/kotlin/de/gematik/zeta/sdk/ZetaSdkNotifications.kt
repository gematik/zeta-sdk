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

package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.notifications.NotificationClient

/**
 * Notification Service client for JVM-based test tooling (test driver, simulators).
 *
 * Push notifications only exist on mobile platforms — the supported consumer accessor is the
 * `notifications()` extension in the Android/iOS source sets. This JVM variant exists so e2e
 * tests can exercise the NS API (pusher/channel management) against a guard deployment without
 * a mobile device, and is not part of the supported public surface.
 */
@InternalZetaApi
fun ZetaSdkClient.notificationsForTesting(): NotificationClient = when (this) {
    is ZetaSdkClientImpl ->
        notificationClient
            ?: error("Notifications are not configured; pass a NotificationConfig in BuildConfig to enable them")
    else -> throw UnsupportedOperationException("notificationsForTesting() requires a client built via ZetaSdk.build()")
}
