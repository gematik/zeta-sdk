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
import de.gematik.zeta.driver.newSdk
import de.gematik.zeta.logging.Log
import de.gematik.zeta.logging.ZetaLogLevel
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.ZetaSdkClientImpl
import de.gematik.zeta.sdk.authentication.AuthMode
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.ResourceScope
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
    val resourceScope: ResourceScope get() = (client as ZetaSdkClientImpl).resourceScope
    val httpClient: ZetaHttpClient by lazy {
        client.httpClient {
            logging(Log.logLevel.toKtorLogLevel())
            disableServerValidation(
                "true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()),
            )
            contentNegotiation(true)
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
    public val aslProdEnv: Boolean = true,
    public val poppToken: String = "",
    public val disableTlsVerification: Boolean = false,
    public val requiredOid: String = "",
    public val filterHostHeaders: Boolean = true,
    val authMode: AuthMode = AuthMode.SMB,
    val oidcBaseUri: String? = null,
    val oidcIdpIss: String? = null,
    val oidcIdpAlias: String? = null,
    val oidcTestKvnr: String? = null,
    val oidcBindingEmail: String? = null,
) {

    public companion object Companion {
        public fun fromEnv(): SdkInstanceConfig {
            return SdkInstanceConfig(
                fachdienstUrl = System.getenv("FACHDIENST_URL") ?: error("FACHDIENST_URL required"),
                smbKeystoreFile = System.getenv("SMB_KEYSTORE_FILE") ?: "",
                smbKeystoreB64 = System.getenv("SMB_KEYSTORE_B64") ?: "",
                smbKeystoreAlias = System.getenv("SMB_KEYSTORE_ALIAS") ?: "",
                smbKeystorePassword = System.getenv("SMB_KEYSTORE_PASSWORD") ?: "",
                aslProdEnv = System.getenv("ASL_PROD")?.toBoolean() ?: true,
                poppToken = System.getenv("POPP_TOKEN") ?: "",
                disableTlsVerification = "true".contentEquals((System.getenv(DISABLE_SERVER_VALIDATION) ?: "").lowercase()),
                requiredOid = System.getenv("REQUIRED_ROLE_OID") ?: "",
                oidcIdpIss = System.getenv("OIDC_IDP_ISS") ?: "",
                oidcIdpAlias = System.getenv("OIDC_IDP_ALIAS") ?: "",
                oidcBaseUri = System.getenv("OIDC_BASE_URI") ?: "",
                oidcTestKvnr = System.getenv("OIDC_TEST_KVNR") ?: "",
                oidcBindingEmail = System.getenv("OIDC_BINDING_EMAIL") ?: "",
                authMode = parseAuthMode(System.getenv("AUTH_MODE")),
                filterHostHeaders = "true".equals(System.getenv("FILTER_HOST_HEADERS"), ignoreCase = true),
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
                    aslProdEnv = props.getProperty("ASL_PROD_1")?.toBoolean() ?: true,
                    poppToken = props.getProperty("POPP_TOKEN_1") ?: "",
                    disableTlsVerification = props.getProperty("DISABLE_SERVER_VALIDATION_1")?.toBoolean() ?: false,
                    oidcIdpIss = props.getProperty("OIDC_IDP_ISS_1") ?: "",
                    oidcIdpAlias = props.getProperty("OIDC_IDP_ALIAS_1") ?: "",
                    oidcBaseUri = props.getProperty("OIDC_BASE_URI_1") ?: "",
                    oidcTestKvnr = props.getProperty("OIDC_TEST_KVNR_1") ?: "",
                    oidcBindingEmail = props.getProperty("OIDC_BINDING_EMAIL_1") ?: "",
                    authMode = parseAuthMode(props.getProperty("AUTH_MODE_1")),
                    requiredOid = props.getProperty("REQUIRED_ROLE_OID_1") ?: "",
                )
            } else {
                Log.i { "Loading test driver configuration from environment variables" }
                fromEnv()
            }
        }
    }
}

internal fun ZetaLogLevel.toKtorLogLevel(): LogLevel = when (this) {
    ZetaLogLevel.DEBUG -> LogLevel.ALL
    ZetaLogLevel.INFO -> LogLevel.INFO
    ZetaLogLevel.WARN -> LogLevel.INFO
    ZetaLogLevel.ERROR -> LogLevel.NONE
    ZetaLogLevel.NONE -> LogLevel.NONE
}

private fun parseAuthMode(raw: String?): AuthMode =
    raw?.let { mode ->
        runCatching { AuthMode.valueOf(mode.uppercase()) }
            .getOrElse { error("Unknown AUTH_MODE: '$mode' (expected 'SMB' or 'OIDC')") }
    } ?: AuthMode.SMB

@Serializable
public data class CreateInstancesRequest(
    val count: Int? = null,
    val autoInit: Boolean = true,
    val instances: List<SdkInstanceConfig>? = null,
    val logLevel: ZetaLogLevel? = ZetaLogLevel.DEBUG,
)
