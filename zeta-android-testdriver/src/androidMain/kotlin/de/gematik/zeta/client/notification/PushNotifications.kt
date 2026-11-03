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
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.notifications
import de.gematik.zeta.sdk.notifications.NotificationClient
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
import de.gematik.zeta.sdk.notifications.model.PusherConfig
import de.gematik.zeta.sdk.notifications.model.PusherData
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val APP_ID_SUFFIX = ".zeta.firebase"
private const val DEFAULT_APP_ID = "zeta-android-testdriver$APP_ID_SUFFIX"

@Serializable
public data class ReceivedNotification(
    val receivedAt: Long,
    val from: String?,
    val keyIdentifier: String?,
    val decrypted: String?,
    val error: String? = null,
)

internal object PushNotifications {
    private val registrationMutex = Mutex()
    private val received = CopyOnWriteArrayList<ReceivedNotification>()
    private var appId: String = DEFAULT_APP_ID

    @Volatile private var notifications: NotificationClient? = null

    @Volatile private var pushGatewayUrl: String? = null

    @Volatile private var registeredPushKey: String? = null

    fun attach(client: ZetaSdkClient, pushGatewayUrl: String, appId: String = "", firebaseProjectId: String = "") {
        this.appId = toPusherAppId(appId.ifBlank { firebaseProjectId })
        Log.i { "PushNotifications attached, appId=${this.appId}, gateway=$pushGatewayUrl" }
        notifications = client.notifications()
        this.pushGatewayUrl = pushGatewayUrl
        registeredPushKey = null
    }

    suspend fun register(): String {
        val token = fetchFcmToken()
        registerWithToken(token, onlyIfRotated = false)
        return "Pusher registered (…${token.takeLast(8)})"
    }

    suspend fun onNewToken(token: String) {
        registerWithToken(token, onlyIfRotated = true)
    }

    suspend fun setAllChannels(status: ChannelStatus): List<Channel> {
        val client = checkNotNull(notifications) { "no SDK session" }
        val channels = client.getAvailableChannels().map { it.copy(status = status) }
        client.setLocalChannels(channels)
        return channels
    }

    suspend fun reset() {
        if (registeredPushKey != null) runCatching { setAllChannels(ChannelStatus.DISABLED) }
        notifications = null
        registeredPushKey = null
        received.clear()
    }

    fun received(): List<ReceivedNotification> = received.toList()

    fun clearReceived() = received.clear()

    suspend fun onMessage(from: String?, data: Map<String, String>, fallbackBody: String?): String? {
        val ciphertext = data["ciphertext"]
        val month = data["time_message_encrypted"]
        val client = notifications

        val result: Result<String?> = when {
            ciphertext == null || month == null -> Result.success(fallbackBody)
            client == null -> Result.failure(IllegalStateException("no SDK session in this process"))
            else -> runCatching { client.decryptPushNotification(ciphertext, month) }
        }

        received += ReceivedNotification(
            receivedAt = System.currentTimeMillis(),
            from = from,
            keyIdentifier = data["key_identifier"],
            decrypted = result.getOrNull(),
            error = result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" },
        )

        return result.getOrNull()
    }

    private suspend fun registerWithToken(token: String, onlyIfRotated: Boolean) = registrationMutex.withLock {
        val client = notifications ?: return@withLock
        if (token == registeredPushKey) return@withLock
        if (onlyIfRotated && registeredPushKey == null) return@withLock

        client.registerPusher(
            PusherConfig(
                pushkey = token,
                appId = appId,
                appDisplayName = "ZETA Demo",
                deviceDisplayName = Build.MODEL,
                lang = "de",
                data = PusherData(url = pushGatewayUrl),
            ),
        )
        registeredPushKey = token
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

    private fun toPusherAppId(raw: String): String {
        val base = raw.trim()
        return when {
            base.isEmpty() -> DEFAULT_APP_ID
            base.endsWith(".firebase") -> base
            else -> "$base$APP_ID_SUFFIX"
        }
    }
}
