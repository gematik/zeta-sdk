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

package de.gematik.zeta.client.data.service.http

import de.gematik.zeta.client.data.service.logger.KtorLogger
import de.gematik.zeta.client.di.DEBUG_LOGGING
import de.gematik.zeta.client.di.DIContainer.AUTH_URL
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.authentication.AuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger

public interface HttpClientProvider {
    public fun provideHttpClient(): HttpClient
    public fun setupEnvUrl(url: String)
}

public class HttpClientProviderImpl : HttpClientProvider {

    private lateinit var httpClient: HttpClient

    override fun provideHttpClient(): HttpClient =
        httpClient

    override fun setupEnvUrl(url: String) {
        httpClient = prepareHttpClient(url)
    }

    private fun prepareHttpClient(url: String): HttpClient {
        return ZetaSdk.build(
            resource = url,
            config = BuildConfig(
                StorageConfig(),
                object : TpmConfig {},
                AuthConfig(
                    listOf(""),
                    "",
                    "",
                    0,
                    AUTH_URL,
                ),
            ),
        )
            .httpClient {
                logging(LogLevel.ALL, if (DEBUG_LOGGING) KtorLogger() else Logger.DEFAULT)
            }
    }
}
