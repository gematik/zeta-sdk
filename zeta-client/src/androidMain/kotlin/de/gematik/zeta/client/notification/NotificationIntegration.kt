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
 * Optional integration point for the push-notification feature.
 *
 * The default build ships **no** implementation of this interface, so push notifications
 * are disabled. When the feature is enabled at build time
 * (`de.gematik.zeta.client.enableNotifications=true`), a Firebase Cloud Messaging backed
 * implementation is compiled into the app and exposed through [ZetaNotifications].
 */
public fun interface NotificationIntegration {

    /**
     * Invoked once from [android.app.Application.onCreate] when the notification feature is enabled.
     */
    public fun onApplicationCreate(application: Application)
}
