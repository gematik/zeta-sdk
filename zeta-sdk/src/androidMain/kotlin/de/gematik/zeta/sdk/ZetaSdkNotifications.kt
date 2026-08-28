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
 * Client for the Notification Service co-deployed with this instance's guard. Lazy and
 * memoized: this accessor performs no I/O; NS discovery and token acquisition happen inside
 * the first operation call.
 *
 * Push notifications require a platform push transport (FCM/APNs), so this accessor exists on
 * Android and iOS only.
 */
fun ZetaSdkClient.notifications(): NotificationClient = when (this) {
    is ZetaSdkClientImpl ->
        notificationClient
            ?: error("Notifications are not configured; pass a NotificationConfig in BuildConfig to enable them")
    else -> throw UnsupportedOperationException("notifications() requires a client built via ZetaSdk.build()")
}
