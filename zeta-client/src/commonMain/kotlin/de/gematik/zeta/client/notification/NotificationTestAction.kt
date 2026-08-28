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

import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.notifications.model.Channel

internal interface NotificationTestAction {
    suspend fun run(sdkClient: ZetaSdkClient): String
    fun hasPusherKey(): Boolean
    fun reset()
    suspend fun subscribeAllChannels(sdkClient: ZetaSdkClient): List<Channel>
    suspend fun unsubscribeAllChannels(sdkClient: ZetaSdkClient): String
}

/**
 * The platform's notification test action, or `null` where push registration is not
 * meaningful — desktop platforms, iOS until an APNs integration exists, and Android builds
 * without the push-notification feature. The UI renders the test button only when non-null.
 *
 * Note: this is deliberately a compile-time (source-set/flavor) capability switch. Runtime
 * checks via `Platform.current` cannot gate this — on Android the shared JVM actual reports
 * `Platform.Jvm.Linux` (os.name is "Linux").
 */
internal expect fun notificationTestAction(): NotificationTestAction?
