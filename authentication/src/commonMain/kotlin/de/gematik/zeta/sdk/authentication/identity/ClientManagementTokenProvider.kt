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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.RecoverableAuthenticationException
import de.gematik.zeta.sdk.authentication.ScopedAccessTokenProvider
import de.gematik.zeta.sdk.authentication.identity.models.ClientManagementScopes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ClientManagementTokenProvider(
    issuerFactory: (AuthenticationStorage) -> AccessTokenProvider,
    persistentStorage: AuthenticationStorage,
    private val endpoints: suspend () -> ClientManagementTokenEndpoints,
    private val baseParams: suspend () -> AccessTokenParams,
    private val dpopKid: suspend () -> String,
    private val stepUp: suspend () -> Unit,
    private val audience: suspend () -> String,
) : ScopedAccessTokenProvider {

    private val store = ClientManagementStorage(persistentStorage)
    private val issuer = issuerFactory(store)
    private val mutex = Mutex()

    override suspend fun getAccessToken(scopes: Set<String>): String = mutex.withLock {
        require(setOf(ClientManagementScopes.CLIENT_MANAGEMENT).containsAll(scopes)) { "Client-management token cannot cover scopes $scopes" }
        try {
            issue()
        } catch (e: RecoverableAuthenticationException) {
            Log.d { "Client-management token issuance failed recoverably (${e.message}), stepping up session" }
            stepUp()
            issue()
        }
    }

    override suspend fun invalidate(scopes: Set<String>) {
        mutex.withLock { store.clearAccessToken() }
    }

    suspend fun clear() {
        mutex.withLock { store.clear() }
    }

    private suspend fun issue(): String {
        val endpoints = endpoints()
        val params = baseParams().copy(scopes = listOf(ClientManagementScopes.CLIENT_MANAGEMENT), audience = audience())
        return issuer.getValidToken(endpoints.tokenEndpoint, endpoints.nonceEndpoint, params, dpopKid())
    }
}

data class ClientManagementTokenEndpoints(
    val tokenEndpoint: String,
    val nonceEndpoint: String,
)
