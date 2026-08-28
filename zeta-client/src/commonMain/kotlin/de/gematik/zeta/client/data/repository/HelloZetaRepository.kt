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

package de.gematik.zeta.client.data.repository

import de.gematik.zeta.client.data.service.HelloZetaService
import de.gematik.zeta.client.data.service.HelloZetaServiceImpl
import de.gematik.zeta.client.di.DIContainer
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.notifications.model.Channel

public interface HelloZetaRepository {
    public suspend fun helloZeta(): String
    public suspend fun forgetAuthorization()
    public suspend fun forgetRegistration()
    public suspend fun logoutAuthorization()
    public suspend fun status(): SdkStatus
    public suspend fun doAuthentication()
    public suspend fun doRegistration()
    public suspend fun doDiscovery()
    public suspend fun registerTestPusher(): String
    public fun isPusherConfigured(): Boolean
    public suspend fun subscribeAllChannels(): List<Channel>
    public suspend fun unsubscribeAllChannels(): String
    public suspend fun changeEmail(newEmail: String): String
}

public class HelloZetaRepositoryImpl(
    public val service: HelloZetaService = HelloZetaServiceImpl(),

) : HelloZetaRepository {
    override suspend fun helloZeta(): String = service.helloZeta()

    override suspend fun doAuthentication() {
        DIContainer.httpClientProvider.authenticate()
    }

    override suspend fun doRegistration() {
        DIContainer.httpClientProvider.register()
    }

    override suspend fun doDiscovery() {
        DIContainer.httpClientProvider.discover()
    }

    override suspend fun forgetAuthorization() {
        DIContainer.httpClientProvider.forget()
    }

    override suspend fun forgetRegistration() {
        DIContainer.httpClientProvider.clearRegistration()
    }

    override suspend fun logoutAuthorization() {
        DIContainer.httpClientProvider.logout()
    }

    override suspend fun status(): SdkStatus {
        return DIContainer.httpClientProvider.status()
    }

    override suspend fun registerTestPusher(): String {
        return DIContainer.httpClientProvider.registerTestPusher()
    }

    override fun isPusherConfigured(): Boolean {
        return DIContainer.httpClientProvider.isPusherConfigured()
    }

    override suspend fun subscribeAllChannels(): List<Channel> {
        return DIContainer.httpClientProvider.subscribeAllChannels()
    }

    override suspend fun unsubscribeAllChannels(): String {
        return DIContainer.httpClientProvider.unsubscribeAllChannels()
    }

    override suspend fun changeEmail(newEmail: String): String {
        return DIContainer.httpClientProvider.changeEmail(newEmail)
    }
}
