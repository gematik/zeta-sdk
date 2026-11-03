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

import de.gematik.zeta.sdk.crypto.AesGcmCipherImpl
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
import de.gematik.zeta.sdk.notifications.model.HistoricNotification
import de.gematik.zeta.sdk.notifications.model.HistoricNotifications
import de.gematik.zeta.sdk.notifications.model.Pusher
import de.gematik.zeta.sdk.notifications.model.PusherConfig
import de.gematik.zeta.sdk.notifications.model.PusherData
import de.gematik.zeta.sdk.notifications.model.PusherEncryption
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationClientImplTest {
    private val encryption = PusherEncryption(
        method = "aes-hmac-sha256",
        timeIssCreated = "2026-08",
        iss = "aa".repeat(32),
        keyIdentifier = "kid-1",
    )

    @Test
    fun getPushers_delegatesToApi() = runTest {
        val stored = listOf(Pusher(pushkey = "k", kind = "http", appId = "app"))
        val api = FakeNotificationApi(pushers = stored)

        val result = client(api).getPushers()

        assertEquals(stored, result)
    }

    @Test
    fun registerPusher_postsHttpPusherWithSdkEncryptionAndRemembersLocalDevice() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val registration = PusherConfig(
            pushkey = "token",
            appId = "de.gematik.fdv",
            appDisplayName = "FdV",
            deviceDisplayName = "Phone",
            profileTag = "tag",
            lang = "de",
            data = PusherData(url = "https://push.example/"),
        )

        client(api, store).registerPusher(registration)

        assertEquals(
            listOf(
                Pusher(
                    pushkey = "token",
                    kind = "http",
                    appId = "de.gematik.fdv",
                    appDisplayName = "FdV",
                    deviceDisplayName = "Phone",
                    profileTag = "tag",
                    lang = "de",
                    data = PusherData(url = "https://push.example/"),
                    encryption = encryption,
                ),
            ),
            api.setPusherCalls,
        )
        assertEquals(LocalPusher("token", "de.gematik.fdv"), store.get())
    }

    @Test
    fun registerPusher_sameLocalIdentityIsNoOp() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val client = client(api, store)
        val registration = PusherConfig(pushkey = "token", appId = "app")
        client.registerPusher(registration)

        client.registerPusher(registration)

        assertEquals(1, api.setPusherCalls.size)
    }

    @Test
    fun registerPusher_sameLocalIdentityWithoutKeyChainReRegisters() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val keyChainStore = InMemoryPushKeyChainStorage()
        val client = client(api, store, keyChainStore)
        val registration = PusherConfig(pushkey = "token", appId = "app")
        client.registerPusher(registration)
        keyChainStore.clear()

        client.registerPusher(registration)

        assertEquals(null, api.setPusherCalls[1].kind)
        assertEquals("http", api.setPusherCalls[2].kind)
        assertEquals(encryption, api.setPusherCalls[2].encryption)
        assertNotNull(keyChainStore.get())
    }

    @Test
    fun registerPusher_storesKeyChainBeforeLocalPusher() = runTest {
        val keyChainStore = InMemoryPushKeyChainStorage()
        val failingStore = object : LocalPusherStorage by InMemoryLocalPusherStorage() {
            override suspend fun save(pusher: LocalPusher) {
                error("disk full")
            }
        }
        val client = client(FakeNotificationApi(), failingStore, keyChainStore)

        assertFailsWith<IllegalStateException> {
            client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))
        }

        val material = derivePushKeyMaterial(encryption.iss.hexToByteArray(), encryption.timeIssCreated)
        val ciphertext = encryptLikeTheFachdienst("still decryptable", material)
        assertEquals("still decryptable", client.decryptPushNotification(ciphertext, encryption.timeIssCreated))
    }

    @Test
    fun registerPusher_replacesPreviousLocalPusher() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val client = client(api, store)
        client.registerPusher(PusherConfig(pushkey = "old", appId = "app"))

        client.registerPusher(PusherConfig(pushkey = "new", appId = "app"))

        assertEquals(null, api.setPusherCalls[1].kind)
        assertEquals("old", api.setPusherCalls[1].pushkey)
        assertEquals("http", api.setPusherCalls[2].kind)
        assertEquals("new", api.setPusherCalls[2].pushkey)
        assertEquals(LocalPusher("new", "app"), store.get())
    }

    @Test
    fun updatePusher_postsWithoutEncryptionAndUpdatesLocalDevice() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val update = PusherConfig(
            pushkey = "token",
            appId = "app",
            appDisplayName = "FdV",
            lang = "en",
        )

        client(api, store).updatePusher(update)

        assertEquals(
            listOf(
                Pusher(
                    pushkey = "token",
                    kind = "http",
                    appId = "app",
                    appDisplayName = "FdV",
                    lang = "en",
                ),
            ),
            api.setPusherCalls,
        )
        assertNull(api.setPusherCalls.single().encryption)
        assertEquals(LocalPusher("token", "app"), store.get())
    }

    @Test
    fun deletePusher_sendsKindNullAndClearsLocalWhenItMatches() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val client = client(api, store)
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))

        client.deletePusher(pushkey = "token", appId = "app")

        assertEquals(null, api.setPusherCalls.last().kind)
        assertNull(store.get())
    }

    @Test
    fun deletePusher_clearsKeyChainWhenItMatches() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val keyChainStore = InMemoryPushKeyChainStorage()
        val client = client(api, store, keyChainStore)
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))

        client.deletePusher(pushkey = "token", appId = "app")

        assertFailsWith<IllegalStateException> { client.decryptPushNotification("cipher", "2026-08") }
        assertEquals(null, keyChainStore.get())
    }

    @Test
    fun deletePusher_keepsLocalWhenAnotherDeviceIsRemoved() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val client = client(api, store)
        client.registerPusher(PusherConfig(pushkey = "mine", appId = "app"))

        client.deletePusher(pushkey = "tablet", appId = "app")

        assertEquals(LocalPusher("mine", "app"), store.get())
    }

    @Test
    fun getAvailableChannels_delegatesToApi() = runTest {
        val channels = listOf(Channel("chat", ChannelStatus.ENABLED))
        val api = FakeNotificationApi(availableChannels = channels)

        assertEquals(channels, client(api).getAvailableChannels())
    }

    @Test
    fun getLocalChannels_usesStoredPushkey() = runTest {
        val api = FakeNotificationApi(
            deviceChannels = mapOf("token" to listOf(Channel("chat", ChannelStatus.DISABLED))),
        )
        val store = InMemoryLocalPusherStorage()
        val client = client(api, store)
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))

        assertEquals(listOf(Channel("chat", ChannelStatus.DISABLED)), client.getLocalChannels())
    }

    @Test
    fun setLocalChannels_usesStoredPushkey() = runTest {
        val api = FakeNotificationApi()
        val store = InMemoryLocalPusherStorage()
        val client = client(api, store)
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))
        val channels = listOf(Channel("chat", ChannelStatus.ENABLED))

        client.setLocalChannels(channels)

        assertEquals(listOf("token" to channels), api.setChannelCalls)
    }

    @Test
    fun getLocalChannels_failsWhenNoLocalPusher() = runTest {
        assertFailsWith<IllegalStateException> { client(FakeNotificationApi()).getLocalChannels() }
    }

    @Test
    fun decryptPushNotification_decryptsAMessageEncryptedForTheRegistrationMonth() = runTest {
        val client = client(FakeNotificationApi())
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))
        val material = derivePushKeyMaterial(encryption.iss.hexToByteArray(), encryption.timeIssCreated)

        val ciphertext = encryptLikeTheFachdienst("hello from the guard", material)

        assertEquals("hello from the guard", client.decryptPushNotification(ciphertext, encryption.timeIssCreated))
    }

    @Test
    fun decryptPushNotification_derivesForwardWhenTheMessageIsFromALaterMonth() = runTest {
        val client = client(FakeNotificationApi())
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))
        val nextMonth = nextYearMonth(encryption.timeIssCreated)
        val material = derivePushKeyMaterial(encryption.iss.hexToByteArray(), encryption.timeIssCreated)
        val nextMaterial = derivePushKeyMaterial(material.sharedSecret, nextMonth)
        val ciphertext = encryptLikeTheFachdienst("next month's message", nextMaterial)

        assertEquals("next month's message", client.decryptPushNotification(ciphertext, nextMonth))
    }

    @Test
    fun decryptPushNotification_failsWhenNoPusherHasBeenRegistered() = runTest {
        assertFailsWith<IllegalStateException> {
            client(FakeNotificationApi()).decryptPushNotification("cipher", "2026-08")
        }
    }

    @Test
    fun decryptPushNotification_rejectsAMalformedMonth() = runTest {
        val client = client(FakeNotificationApi())
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))

        assertFailsWith<IllegalArgumentException> {
            client.decryptPushNotification("cipher", "not-a-month")
        }
    }

    @Test
    fun getNotification_delegatesToApi() = runTest {
        val notification = HistoricNotification("550e8400-e29b-41d4-a716-446655440000", buildJsonObject { put("event", "new") })
        val api = FakeNotificationApi(history = mapOf("token" to listOf(notification)))

        assertEquals(notification, client(api).getNotification(notification.identifier))
    }

    @Test
    fun getNotifications_usesStoredPushkey() = runTest {
        val own = HistoricNotification("id-1")
        val api = FakeNotificationApi(history = mapOf("token" to listOf(own), "other-device" to listOf(HistoricNotification("id-2"))))
        val client = client(api)
        client.registerPusher(PusherConfig(pushkey = "token", appId = "app"))

        assertEquals(HistoricNotifications(listOf(own)), client.getNotifications())
    }

    @Test
    fun getNotifications_failsWhenNoLocalPusher() = runTest {
        assertFailsWith<IllegalStateException> { client(FakeNotificationApi()).getNotifications() }
    }

    @Test
    fun defaultPusherEncryption_hasExpectedShape() {
        val created = defaultPusherEncryption()

        assertEquals("aes-hmac-sha256", created.method)
        assertTrue(Regex("""\d{4}-\d{2}""").matches(created.timeIssCreated))
        assertEquals(64, created.iss.length)
        assertTrue(created.keyIdentifier.isNotBlank())
    }

    private fun client(
        api: NotificationApiClient,
        store: LocalPusherStorage = InMemoryLocalPusherStorage(),
        keyChainStorage: PushKeyChainStorage = InMemoryPushKeyChainStorage(),
    ) = NotificationClientImpl(api, store, { encryption }, keyChainStorage)
}

/** Builds a push payload the way the Fachdienst would (`A_27610` padding, then AES/GCM). */
private fun encryptLikeTheFachdienst(message: String, material: PushKeyMaterial): String {
    val padded = padPushMessageForTest(message)
    val packed = AesGcmCipherImpl().encrypt(material.aesKey, padded)
    return Base64.encode(packed)
}

private fun padPushMessageForTest(message: String): ByteArray {
    val messageBytes = message.encodeToByteArray()
    val paddedSize = 1024
    val headerLen = 6
    val padLength = paddedSize - headerLen - messageBytes.size
    return "PNM1".encodeToByteArray() +
        byteArrayOf((padLength shr 8).toByte(), (padLength and 0xFF).toByte()) +
        ByteArray(padLength) { ' '.code.toByte() } +
        messageBytes
}

private class FakeNotificationApi(
    var pushers: List<Pusher> = emptyList(),
    var availableChannels: List<Channel> = emptyList(),
    var deviceChannels: Map<String, List<Channel>> = emptyMap(),
    var history: Map<String, List<HistoricNotification>> = emptyMap(),
) : NotificationApiClient {
    val setPusherCalls = mutableListOf<Pusher>()
    val setChannelCalls = mutableListOf<Pair<String, List<Channel>>>()

    override suspend fun getPushers(): List<Pusher> = pushers

    override suspend fun setPusher(pusher: Pusher) {
        setPusherCalls += pusher
    }

    override suspend fun getChannels(): List<Channel> = availableChannels

    override suspend fun getChannel(pushkey: String): List<Channel> = deviceChannels[pushkey].orEmpty()

    override suspend fun setChannel(pushkey: String, channels: List<Channel>) {
        setChannelCalls += pushkey to channels
    }

    override suspend fun getHistoricNotification(notificationId: String): HistoricNotification =
        history.values.flatten().single { it.identifier == notificationId }

    override suspend fun getHistoricNotifications(pushkey: String): List<HistoricNotification> = history[pushkey].orEmpty()
}
