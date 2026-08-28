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

import de.gematik.zeta.sdk.notifications.model.HistoricNotification
import de.gematik.zeta.sdk.notifications.model.HistoricNotifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HistoryTest {
    private val json = Json { encodeDefaults = true }
    private val payload = buildJsonObject {
        put("event", "new_message")
        put("body", "hello")
    }
    private val otherPayload = buildJsonObject { put("event", "read") }

    @Test
    fun historicNotification_equalsHashCodeCopyAndComponents() {
        val notification = HistoricNotification(identifier = "id-1", payload = payload)
        val same = notification.copy()

        assertEquals(notification, notification)
        assertEquals(notification, same)
        assertEquals(notification.hashCode(), same.hashCode())
        assertEquals("id-1", notification.component1())
        assertEquals(payload, notification.component2())
        assertNotEquals(notification, notification.copy(identifier = "id-2"))
        assertNotEquals(notification, notification.copy(payload = otherPayload))
        assertFalse(notification.equals("notification"))
        assertNotNull(notification.toString())
    }

    @Test
    fun historicNotification_roundTripsThroughJson() {
        val notification = HistoricNotification(identifier = "id-1", payload = payload)

        val encoded = json.encodeToString(notification)
        val decoded = json.decodeFromString<HistoricNotification>(encoded)

        assertEquals("""{"identifier":"id-1","payload":{"event":"new_message","body":"hello"}}""", encoded)
        assertEquals(notification, decoded)
    }

    @Test
    fun historicNotifications_defaultsToEmptyList() {
        val empty = HistoricNotifications()

        assertTrue(empty.notifications.isEmpty())
        assertEquals(HistoricNotifications(emptyList()), empty)
        assertEquals(HistoricNotifications().hashCode(), empty.hashCode())
    }

    @Test
    fun historicNotifications_equalsHashCodeCopyAndComponents() {
        val item = HistoricNotification(identifier = "id-1", payload = payload)
        val notifications = HistoricNotifications(listOf(item))
        val same = notifications.copy()

        assertEquals(notifications, notifications)
        assertEquals(notifications, same)
        assertEquals(notifications.hashCode(), same.hashCode())
        assertEquals(listOf(item), notifications.component1())
        assertNotEquals(notifications, HistoricNotifications())
        assertNotEquals(notifications, notifications.copy(notifications = emptyList()))
        assertFalse(notifications.equals("notifications"))
        assertNotNull(notifications.toString())
    }

    @Test
    fun historicNotifications_roundTripsThroughJson() {
        val notifications = HistoricNotifications(
            listOf(HistoricNotification(identifier = "id-1", payload = payload)),
        )

        val encoded = json.encodeToString(notifications)
        val decoded = json.decodeFromString<HistoricNotifications>(encoded)

        assertEquals(
            """{"notifications":[{"identifier":"id-1","payload":{"event":"new_message","body":"hello"}}]}""",
            encoded,
        )
        assertEquals(notifications, decoded)
    }

    @Test
    fun historicNotifications_decodesMissingFieldAsEmptyList() {
        val decoded = json.decodeFromString<HistoricNotifications>("{}")

        assertEquals(HistoricNotifications(), decoded)
        assertTrue(decoded.notifications.isEmpty())
    }
}
