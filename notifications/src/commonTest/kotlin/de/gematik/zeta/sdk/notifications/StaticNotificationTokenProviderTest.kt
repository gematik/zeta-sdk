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

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticNotificationTokenProviderTest {
    @Test
    fun getAccessToken_returnsInjectedToken() = runTest {
        val provider = StaticNotificationTokenProvider("static-token")

        assertEquals("static-token", provider.getAccessToken(NotificationScopes.ALL))
    }

    @Test
    fun getAccessToken_invokesSuspendSupplierOnEachCall() = runTest {
        var calls = 0
        val provider = StaticNotificationTokenProvider {
            calls++
            "token-$calls"
        }

        assertEquals("token-1", provider.getAccessToken(emptySet()))
        assertEquals("token-2", provider.getAccessToken(setOf(NotificationScopes.PUSHER_READ)))
        assertEquals(2, calls)
    }

    @Test
    fun invalidate_isNoOp_andDoesNotChangeReturnedToken() = runTest {
        val provider = StaticNotificationTokenProvider("static-token")

        provider.invalidate(NotificationScopes.ALL)

        assertEquals("static-token", provider.getAccessToken(NotificationScopes.ALL))
    }
}
