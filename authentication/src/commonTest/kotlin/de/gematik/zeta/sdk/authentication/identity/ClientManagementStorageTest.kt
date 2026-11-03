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

package de.gematik.zeta.sdk.authentication.identity

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientManagementStorageTest {

    private val delegate = FakeAuthenticationStorage()
    private val store = ClientManagementStorage(delegate)

    @Test
    fun saveAccessTokens_persistsAccessTokenButNeverTheRefreshToken() = runTest {
        store.saveAccessTokens("at", "rt", expiresAt = 100, refreshExpiresAt = 200)

        assertEquals("at", delegate.accessToken)
        assertEquals(100, delegate.expiresAt)
        assertEquals("", delegate.refreshToken)
        assertNull(delegate.refreshExpiresAt)
    }

    @Test
    fun getRefreshToken_isAlwaysNull_evenIfDelegateHoldsOne() = runTest {
        delegate.refreshToken = "leaked-rt"
        delegate.refreshExpiresAt = 200

        assertNull(store.getRefreshToken())
        assertNull(store.getRefreshTokenExpiration())
    }

    @Test
    fun getAccessTokenAndExpiration_delegate() = runTest {
        delegate.accessToken = "at"
        delegate.expiresAt = 100

        assertEquals("at", store.getAccessToken())
        assertEquals("100", store.getAccessTokenExpiration())
    }

    @Test
    fun clearAccessToken_delegatesWithoutFullClear() = runTest {
        delegate.accessToken = "at"

        store.clearAccessToken()

        assertNull(delegate.accessToken)
        assertEquals(1, delegate.accessTokenClears)
        assertEquals(0, delegate.clears)
    }

    @Test
    fun clear_delegates() = runTest {
        delegate.accessToken = "at"

        store.clear()

        assertNull(delegate.accessToken)
        assertEquals(1, delegate.clears)
    }
}
