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

package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.identity.ClientManagement
import de.gematik.zeta.sdk.authentication.identity.EmailChallenge
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient

internal class ClientManagementOperations(
    private val discover: suspend () -> Unit,
    private val isRegistered: suspend () -> Boolean,
    private val currentClientId: suspend () -> String?,
    private val clearRegistration: suspend () -> Unit,
    private val clientManagement: () -> ClientManagement,
) {
    suspend fun changeEmail(newEmail: String): ChangeEmailResponse {
        ensureRegistered()
        return clientManagement().changeEmail(newEmail)
    }

    suspend fun resendEmailChangeCode(): EmailChallenge {
        ensureRegistered()
        return clientManagement().resendEmailChangeCode()
    }

    suspend fun verifyEmailChange(code: String) {
        ensureRegistered()
        clientManagement().verifyEmailChange(code)
    }

    suspend fun listClients(): List<RegisteredClient> {
        ensureRegistered()
        return clientManagement().listClients()
    }

    suspend fun deleteClient(id: String) {
        require(id.isNotBlank()) { "client id must not be blank" }
        ensureRegistered()

        val ownClientId = currentClientId()
        clientManagement().deleteClient(id)

        if (id == ownClientId) {
            clearRegistration()
        }
    }

    private suspend fun ensureRegistered() {
        discover()
        check(isRegistered()) { NOT_REGISTERED }
    }

    companion object {
        const val NOT_REGISTERED = "Client not registered"
    }
}
