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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.gematik.zeta.client.MainActivity
import de.gematik.zeta.client.R
import de.gematik.zeta.logging.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "ZetaFirebaseMsgService"
private const val INCOMING_TAG = "ZetaIncomingMessage"
private const val PENDING_INTENT_REQUEST_CODE = 0

public class ZetaFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(tag = INCOMING_TAG) {
            "Received message from ${remoteMessage.from}, data=${remoteMessage.data}, " +
                "notification=${remoteMessage.notification?.body}"
        }

        Log.d(tag = TAG) { "From: ${remoteMessage.from}" }

        if (remoteMessage.data.isNotEmpty()) {
            Log.d(tag = TAG) { "Message data payload: ${remoteMessage.data}" }
        }

        remoteMessage.notification?.let {
            Log.d(tag = TAG) { "Message notification body: ${it.body}" }
        }

        val notificationBody = remoteMessage.notification?.body ?: buildBodyFromData(remoteMessage.data)
        notificationBody?.let { sendNotification(it, remoteMessage.messageId) }
    }

    private fun buildBodyFromData(data: Map<String, String>): String? {
        if (data.isEmpty()) return null
        val sender = data["sender"] ?: return null
        val room = data["room_name"] ?: data["room_id"]
        return if (room != null) "New message from $sender in $room" else "New message from $sender"
    }

    override fun onNewToken(token: String) {
        Log.d(tag = TAG) { "FCM token refreshed" }
        scope.launch {
            try {
                reregisterPusherForNewFcmToken(token)
            } catch (t: Throwable) {
                Log.e(throwable = t, tag = TAG) { "Failed to register pusher after FCM token refresh" }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sendNotification(messageBody: String, messageId: String?) {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val channelId = getString(R.string.default_notification_channel_id)
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(getString(R.string.fcm_message))
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            notificationManager.createNotificationChannel(channel)
        }

        // One id per message so notifications accumulate instead of replacing each other.
        // Deriving it from the FCM message id keeps a redelivered message from showing twice;
        // the timestamp fallback covers messages without an id.
        val notificationId = messageId?.hashCode() ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
}
