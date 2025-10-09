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

package de.gematik.zeta.client.di

import de.gematik.zeta.client.config.getConfig
import de.gematik.zeta.client.data.repository.PrescriptionRepository
import de.gematik.zeta.client.data.repository.PrescriptionRepositoryImpl
import de.gematik.zeta.client.data.service.PrescriptionService
import de.gematik.zeta.client.data.service.PrescriptionServiceImpl
import de.gematik.zeta.client.data.service.fake.FakePrescriptionService
import de.gematik.zeta.client.data.service.http.HttpClientProvider
import de.gematik.zeta.client.data.service.http.HttpClientProviderImpl
import de.gematik.zeta.client.data.service.logger.KtorLogger
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.authentication.AuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import kotlinx.coroutines.runBlocking

private const val USE_FAKE_SERVICES = false
internal const val DEBUG_LOGGING = true

public object DIContainer {
    public val httpClientProvider: HttpClientProvider = HttpClientProviderImpl()

    public val prescriptionService: PrescriptionService =
        if (USE_FAKE_SERVICES) {
            FakePrescriptionService()
        } else {
            PrescriptionServiceImpl()
        }

    public val prescriptionRepository: PrescriptionRepository =
        PrescriptionRepositoryImpl(prescriptionService)

    public val zetaHttpClient: HttpClient get() = runBlocking {
        ZetaSdk.build(
            resource = ENVIRONMENTS.firstOrNull() ?: "",
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

    init {
        if (DEBUG_LOGGING) {
            Log.initDebugLogger()
        }
    }
    public val ENVIRONMENTS: List<String> = getUrlEnvironments()
    public val AUTH_URL: String = getConfig("AUTH_URL") ?: ""

    private fun getUrlEnvironments(): List<String> {
        return getConfig("ENVIRONMENTS")
            ?.trim()
            ?.split(" ")
            ?.filter { it.isNotBlank() } ?: emptyList()
    }
}
