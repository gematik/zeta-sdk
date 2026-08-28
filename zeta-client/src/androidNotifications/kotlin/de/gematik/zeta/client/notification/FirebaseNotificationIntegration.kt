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

import android.app.Application

/**
 * Firebase Cloud Messaging backed implementation of [NotificationIntegration].
 *
 * This class is only compiled into the app when the push-notification feature is enabled at
 * build time. It is instantiated by this source set's [createNotificationIntegration] variant,
 * which [ZetaNotifications] calls.
 *
 * The FCM push key is deliberately never logged. To inspect it during development, set a
 * breakpoint in [ZetaFirebaseMessagingService.onNewToken] or evaluate
 * `FirebaseMessaging.getInstance().token` in the debugger.
 */
public class FirebaseNotificationIntegration : NotificationIntegration {

    override fun onApplicationCreate(application: Application) {
        // Nothing to do at startup; message handling lives in ZetaFirebaseMessagingService.
    }
}
