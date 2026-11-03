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

import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.notifications
import de.gematik.zeta.sdk.notifications.NotificationClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val APP_ID = "zeta-notifications-test.zeta.firebase"

internal fun createNotificationTestAction(): NotificationTestAction? = FcmNotificationTestAction

internal suspend fun reregisterPusherForNewFcmToken(token: String) {
    FcmNotificationTestAction.registerWithToken(token)
}

/**
 * Decrypts an incoming FCM push payload using the SDK session from the last app interaction in
 * this process (set via [FcmNotificationTestAction]'s test actions). Returns `null` when no
 * session is available yet — e.g. a push delivered to a process where the app hasn't been opened
 * since launch — since [ZetaFirebaseMessagingService] has no way to build one on its own.
 */
internal suspend fun decryptIncomingPush(ciphertext: String, timeMessageEncrypted: String): String? =
    FcmNotificationTestAction.decryptIncomingPush(ciphertext, timeMessageEncrypted)

private object FcmNotificationTestAction : PusherNotificationTestAction(APP_ID, Build.MODEL) {
    override suspend fun fetchPushToken(): String = fetchFcmToken()

    override fun notificationsOf(sdkClient: ZetaSdkClient): NotificationClient = sdkClient.notifications()
}

private suspend fun fetchFcmToken(): String = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token unavailable"))
        }
    }
}
