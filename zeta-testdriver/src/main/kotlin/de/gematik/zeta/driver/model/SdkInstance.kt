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

package de.gematik.zeta.driver.model

import de.gematik.zeta.driver.DISABLE_SERVER_VALIDATION
import de.gematik.zeta.driver.logger
import de.gematik.zeta.driver.newSdk
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Properties

public data class SdkInstance(
    val id: Int,
    val config: SdkInstanceConfig,
    val store: SdkStorage,
    var state: InstanceState = InstanceState.CREATED,
    var error: String? = null,
) {
    internal val client: ZetaSdkClient = newSdk(store, config)
    val httpClient: ZetaHttpClient by lazy {
        client.httpClient {
            logging(LogLevel.ALL, logger)
            disableServerValidation(
                "true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()),
            )
            dispatcher(300, 300)
        }
    }
}

public enum class InstanceState { CREATED, INITIALIZING, READY, FAILED }

@Serializable
public data class SdkInstanceConfig(
    public val id: Int? = null,
    public var fachdienstUrl: String? = "",
    public val smbKeystoreFile: String = "",
    public val smbKeystoreB64: String = "",
    public val smbKeystoreAlias: String = "",
    public val smbKeystorePassword: String = "",
    public val smcbBaseUrl: String = "",
    public val smcbCardHandle: String = "",
    public val smcbClientSystemId: String = "",
    public val smcbMandantId: String = "",
    public val smcbUserId: String = "",
    public val smcbWorkspaceId: String = "",
    public val aslProdEnv: Boolean = true,
    public val poppToken: String = "",
) {
    public companion object Companion {
        public fun fromEnv(): SdkInstanceConfig {
            return SdkInstanceConfig(
                fachdienstUrl = System.getenv("FACHDIENST_URL") ?: error("FACHDIENST_URL required"),
                smbKeystoreFile = System.getenv("SMB_KEYSTORE_FILE") ?: "",
                smbKeystoreB64 = System.getenv("SMB_KEYSTORE_B64") ?: "",
                smbKeystoreAlias = System.getenv("SMB_KEYSTORE_ALIAS") ?: "",
                smbKeystorePassword = System.getenv("SMB_KEYSTORE_PASSWORD") ?: "",
                smcbBaseUrl = System.getenv("SMCB_BASE_URL") ?: "",
                smcbCardHandle = System.getenv("SMCB_CARD_HANDLE") ?: "",
                smcbClientSystemId = System.getenv("SMCB_CLIENT_SYSTEM_ID") ?: "",
                smcbMandantId = System.getenv("SMCB_MANDANT_ID") ?: "",
                smcbUserId = System.getenv("SMCB_USER_ID") ?: "",
                smcbWorkspaceId = System.getenv("SMCB_WORKSPACE_ID") ?: "",
                aslProdEnv = System.getenv("ASL_PROD")?.toBoolean() ?: true,
                poppToken = System.getenv("POPP_TOKEN") ?: "",
            )
        }

        public fun fromFileOrEnv(): SdkInstanceConfig {
            val configFilePath = System.getenv("CONFIG_FILE") ?: return fromEnv()
            val configFile = File(configFilePath)

            if (!configFile.exists()) {
                Log.i { "Loading test driver configuration from environment variables" }
                return fromEnv()
            }

            val props = Properties().apply {
                configFile.inputStream().use { load(it) }
            }

            val instance1Url = props.getProperty("FACHDIENST_URL_1")
            return if (instance1Url != null) {
                Log.i { "Loading test driver configuration from CONFIG FILE" }
                SdkInstanceConfig(
                    fachdienstUrl = instance1Url,
                    smbKeystoreFile = props.getProperty("SMB_KEYSTORE_FILE_1") ?: "",
                    smbKeystoreB64 = props.getProperty("SMB_KEYSTORE_B64_1") ?: "",
                    smbKeystoreAlias = props.getProperty("SMB_KEYSTORE_ALIAS_1") ?: "",
                    smbKeystorePassword = props.getProperty("SMB_KEYSTORE_PASSWORD_1") ?: "",
                    smcbBaseUrl = props.getProperty("SMCB_BASE_URL_1") ?: "",
                    smcbCardHandle = props.getProperty("SMCB_CARD_HANDLE_1") ?: "",
                    smcbClientSystemId = props.getProperty("SMCB_CLIENT_SYSTEM_ID_1") ?: "",
                    smcbMandantId = props.getProperty("SMCB_MANDANT_ID_1") ?: "",
                    smcbUserId = props.getProperty("SMCB_USER_ID_1") ?: "",
                    smcbWorkspaceId = props.getProperty("SMCB_WORKSPACE_ID_1") ?: "",
                    aslProdEnv = props.getProperty("ASL_PROD_1")?.toBoolean() ?: true,
                    poppToken = props.getProperty("POPP_TOKEN_1") ?: "",
                )
            } else {
                Log.i { "Loading test driver configuration from environment variables" }
                fromEnv()
            }
        }
    }
}

@Serializable
public data class CreateInstancesRequest(
    val count: Int? = null,
    val autoInit: Boolean = true,
    val instances: List<SdkInstanceConfig>? = null,
)
