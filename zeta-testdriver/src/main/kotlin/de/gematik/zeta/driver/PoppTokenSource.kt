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

import de.gematik.zeta.driver.core.popp.PoppActor
import de.gematik.zeta.driver.core.popp.PoppGeneratorConfig
import de.gematik.zeta.driver.core.popp.PoppTokenProvider
import de.gematik.zeta.driver.core.popp.SmcbActorReader
import de.gematik.zeta.driver.core.popp.defaultPoppTokenProvider
import de.gematik.zeta.driver.core.popp.logStaticPoppTokenFallback
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.sdk.authentication.AuthMode
import kotlin.io.encoding.Base64

public fun interface PoppTokenSource {
    public suspend fun generatedTokenFor(config: SdkInstanceConfig): String?
}

internal class GeneratorPoppTokenSource(private val provider: PoppTokenProvider) : PoppTokenSource {
    override suspend fun generatedTokenFor(config: SdkInstanceConfig): String? {
        if (config.authMode == AuthMode.OIDC) return null
        val generated = provider.tokenFor(config.poppGeneratorConfig()) { config.readPoppActor() }
        if (generated == null) logStaticPoppTokenFallback(config.poppTokenGeneratorUrl, config.poppToken)
        return generated
    }
}

internal val defaultPoppTokenSource: PoppTokenSource by lazy {
    val disableServerValidation = "true".equals(System.getenv(DISABLE_SERVER_VALIDATION), ignoreCase = true)
    GeneratorPoppTokenSource(defaultPoppTokenProvider(disableServerValidation))
}

internal fun SdkInstanceConfig.poppGeneratorConfig(): PoppGeneratorConfig =
    PoppGeneratorConfig(poppTokenGeneratorUrl, proofMethod, patientId, insurerId)

internal fun SdkInstanceConfig.readPoppActor(
    fromBytes: (ByteArray, String, String) -> PoppActor = SmcbActorReader::fromKeystoreBytes,
    fromFile: (String, String, String) -> PoppActor = SmcbActorReader::fromKeystoreFile,
): PoppActor = when {
    smbKeystoreB64.isNotEmpty() -> fromBytes(Base64.decode(smbKeystoreB64), smbKeystoreAlias, smbKeystorePassword)
    smbKeystoreFile.isNotEmpty() -> fromFile(smbKeystoreFile, smbKeystoreAlias, smbKeystorePassword)
    else -> error("no SM(C)-B keystore configured")
}

internal fun SdkInstanceConfig.withPoppToken(generated: String?): SdkInstanceConfig =
    if (generated == null) this else copy(poppToken = generated)
