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

/**
 * Entry point for the optional push-notification feature.
 *
 * The Firebase-specific code lives in the `androidNotifications` source set, which is only
 * compiled into the app when the feature is enabled at build time
 * (`de.gematik.zeta.client.enableNotifications=true`). The build script selects exactly one
 * variant of [createNotificationIntegration] — the Firebase-backed one from
 * `src/androidNotifications` or the no-op one from `src/androidNoNotifications` — so the
 * default build has no compile- or run-time dependency on Firebase and no reflection is
 * involved.
 */
public object ZetaNotifications {

    /**
     * The active [NotificationIntegration], or `null` when the feature is disabled at build time.
     */
    public val integration: NotificationIntegration? by lazy { createNotificationIntegration() }

    /** `true` when the push-notification feature is enabled at build time. */
    public val isEnabled: Boolean get() = integration != null
}
