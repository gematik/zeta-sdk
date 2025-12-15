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

package de.gematik.zeta.sdk.flow

import de.gematik.zeta.sdk.asl.AslStorage
import de.gematik.zeta.sdk.asl.AslStorageImpl
import de.gematik.zeta.sdk.authentication.AuthenticationStorage
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorage
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorageImpl
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.configuration.ConfigurationStorageImpl
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.tpm.TpmStorage
import de.gematik.zeta.sdk.tpm.TpmStorageImpl

interface FlowContext {
    val resource: String
    val client: ForwardingClient
    val configurationStorage: ConfigurationStorage
    val clientRegistrationStorage: ClientRegistrationStorage
    val authenticationStorage: AuthenticationStorage
    val tpmStorage: TpmStorage
    val aslStorage: AslStorage
}

/**
 * Shared context passed to handlers. Provides a HTTP client
 * (that executes the current request builder) and access to storage.
 */
class FlowContextImpl(
    override val resource: String,
    override val client: ForwardingClient,
    storage: SdkStorage,
    override val configurationStorage: ConfigurationStorage = ConfigurationStorageImpl(storage),
    override val clientRegistrationStorage: ClientRegistrationStorage = ClientRegistrationStorageImpl(storage),
    override val authenticationStorage: AuthenticationStorage = AuthenticationStorageImpl(storage),
    override val tpmStorage: TpmStorage = TpmStorageImpl(storage),
    override val aslStorage: AslStorage = AslStorageImpl(storage),

) : FlowContext
