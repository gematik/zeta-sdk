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

package de.gematik.zeta.driver

import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.logging.Log
import java.io.File
import java.util.Properties

public interface ConfigProvider {
    public fun loadConfig(id: Int): SdkInstanceConfig
    public fun mergeWithEnvFallback(provided: SdkInstanceConfig): SdkInstanceConfig
}

public class EnvConfigProvider(
    private val configFile: File? = System.getenv("CONFIG_FILE")?.let { File(it) },
) : ConfigProvider {

    override fun loadConfig(id: Int): SdkInstanceConfig {
        return if (configFile?.exists() == true) {
            Log.i { "[LOAD-DRIVER-CONFIG] Loading config from ${configFile.absolutePath}" }
            val props = Properties().apply {
                configFile.inputStream().use { load(it) }
            }
            buildConfigFromProperties(props, id)
        } else {
            Log.i { "[LOAD-DRIVER-CONFIG] No INSTANCE_CONFIG file, using global env for instance $id" }
            SdkInstanceConfig.fromEnv()
        }
    }

    override fun mergeWithEnvFallback(provided: SdkInstanceConfig): SdkInstanceConfig {
        fun env(key: String) = System.getenv(key) ?: ""

        val keystoreFile = provided.smbKeystoreFile.ifBlank { "" }
        val keystoreB64 = provided.smbKeystoreB64.ifBlank { "" }

        val (resolvedFile, resolvedB64) = when {
            keystoreFile.isNotBlank() -> keystoreFile to ""
            keystoreB64.isNotBlank() -> "" to keystoreB64
            else -> {
                val envFile = env("SMB_KEYSTORE_FILE")
                val envB64 = env("SMB_KEYSTORE_B64")
                when {
                    envFile.isNotBlank() -> envFile to ""
                    else -> "" to envB64
                }
            }
        }

        return provided.copy(
            smbKeystoreFile = resolvedFile,
            smbKeystoreB64 = resolvedB64,
            fachdienstUrl = provided.fachdienstUrl?.ifBlank { env("FACHDIENST_URL") }
                ?: env("FACHDIENST_URL").ifBlank { null },
            smbKeystoreAlias = provided.smbKeystoreAlias.ifBlank { env("SMB_KEYSTORE_ALIAS") },
            smbKeystorePassword = provided.smbKeystorePassword.ifBlank { env("SMB_KEYSTORE_PASSWORD") },
            poppToken = provided.poppToken.ifBlank { env("POPP_TOKEN") },
            poppTokenGeneratorUrl = provided.poppTokenGeneratorUrl.ifBlank { env("POPP_TOKEN_GENERATOR_URL") },
            proofMethod = provided.proofMethod.ifBlank { env("POPP_PROOF_METHOD") },
            patientId = provided.patientId.ifBlank { env("POPP_PATIENT_ID") },
            insurerId = provided.insurerId.ifBlank { env("POPP_INSURER_ID") },
            aslProdEnv = System.getenv("ASL_PROD")?.toBoolean() ?: provided.aslProdEnv,
        )
    }

    internal fun buildConfigFromProperties(props: Properties, id: Int): SdkInstanceConfig {
        fun getConfigValue(key: String): String? {
            return props.getProperty("${key}_$id")
                ?: props.getProperty("${key}_1")
                ?: System.getenv(key)
        }

        return SdkInstanceConfig(
            fachdienstUrl = getConfigValue("FACHDIENST_URL")
                ?: error("Missing FACHDIENST_URL for instance $id"),
            smbKeystoreFile = getConfigValue("SMB_KEYSTORE_FILE") ?: "",
            smbKeystoreB64 = getConfigValue("SMB_KEYSTORE_B64") ?: "",
            smbKeystoreAlias = getConfigValue("SMB_KEYSTORE_ALIAS") ?: "",
            smbKeystorePassword = getConfigValue("SMB_KEYSTORE_PASSWORD") ?: "",
            aslProdEnv = getConfigValue("ASL_PROD")?.toBoolean() ?: true,
            poppToken = getConfigValue("POPP_TOKEN") ?: "",
            requiredOid = getConfigValue("REQUIRED_ROLE_OID") ?: "",
            poppTokenGeneratorUrl = getConfigValue("POPP_TOKEN_GENERATOR_URL") ?: "",
            proofMethod = getConfigValue("POPP_PROOF_METHOD") ?: "",
            patientId = getConfigValue("POPP_PATIENT_ID") ?: "",
            insurerId = getConfigValue("POPP_INSURER_ID") ?: "",
        )
    }
}
