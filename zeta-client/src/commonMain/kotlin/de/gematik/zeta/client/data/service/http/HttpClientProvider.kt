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

import de.gematik.zeta.client.data.service.smb.HardcodedTokenProvider
import de.gematik.zeta.client.di.DIContainer.ASL_PROD
import de.gematik.zeta.client.di.DIContainer.DISABLE_SERVER_VALIDATION
import de.gematik.zeta.client.di.DIContainer.SMB_KEYSTORE_CREDENTIALS
import de.gematik.zeta.client.di.DIContainer.SMCB_CONNECTOR_CONFIG
import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger

public interface HttpClientProvider {
    public fun provideHttpClient(): ZetaHttpClient
    public fun setupEnvUrl(url: String)
    public suspend fun forget()
}

public class HttpClientProviderImpl : HttpClientProvider {
    private lateinit var httpClient: ZetaHttpClient
    private lateinit var sdkClient: ZetaSdkClient

    override fun provideHttpClient(): ZetaHttpClient =
        httpClient

    override fun setupEnvUrl(url: String) {
        httpClient = prepareHttpClient(url)
    }

    override suspend fun forget() {
        sdkClient.forget()
    }

    private fun prepareHttpClient(url: String): ZetaHttpClient {
        sdkClient = ZetaSdk.build(
            resource = url,
            config = BuildConfig(
                "demo-client",
                productVersion = "0.5.0",
                "sdk-client",
                StorageConfig(),
                object : TpmConfig {},
                AuthConfig(
                    listOf(
                        "zero:audience",
                    ),
                    30,
                    ASL_PROD,
                    when {
                        SMB_KEYSTORE_CREDENTIALS.keystoreFile.isNotEmpty() ->
                            SmbTokenProvider(SMB_KEYSTORE_CREDENTIALS)

                        SMCB_CONNECTOR_CONFIG.baseUrl.isNotEmpty() ->
                            SmcbTokenProvider(SMCB_CONNECTOR_CONFIG)

                        else ->
                            HardcodedTokenProvider()
                    },
                    AttestationConfig.software(),
                ),
                getPlatformProduct(),
                ZetaHttpClientBuilder()
                    .disableServerValidation(DISABLE_SERVER_VALIDATION)
                    .logging(
                        LogLevel.ALL,
                        object : Logger {
                            override fun log(message: String) {
                                println(message)
                            }
                        },
                    ),
            ),
        )

        return sdkClient.httpClient {
            logging(
                LogLevel.ALL,
                object : Logger {
                    override fun log(message: String) {
                        println(message)
                    }
                },
            )
            disableServerValidation(DISABLE_SERVER_VALIDATION)
        }
    }

    private fun getPlatformProduct(): PlatformProductId {
        return when (val plat = platform()) {
            is Platform.Jvm.Macos, Platform.Native.Macos -> PlatformProductId.AppleProductId("apple", "macos", listOf())
            is Platform.Jvm.Linux -> PlatformProductId.LinuxProductId("linux", "", "demo-client", "0.5.0")
            is Platform.Jvm.Windows -> PlatformProductId.WindowsProductId("windows", "", "demo-client")
            else -> error("Unknown platform: $plat")
        }
    }
}
