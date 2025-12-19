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

import de.gematik.zeta.sdk.attestation.model.ClientSelfAssessment
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession

public interface ZetaSdkClient {
    suspend fun discover(): Result<Unit>
    suspend fun register(): Result<Unit>
    suspend fun authenticate(): Result<Unit>
    fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit = {}): ZetaHttpClient
    suspend fun <R> ws(
        targetUrl: String,
        builder: ZetaHttpClientBuilder.() -> Unit = {},
        customHeaders: Map<String, String>? = null,
        block: suspend DefaultClientWebSocketSession.() -> R,
    )
    suspend fun close(): Result<Unit>
}

data class StorageConfig(
    val provider: SdkStorage? = null,
    // TODO: fix clients to generate their own B64 AES 256 key
    val aesB64Key: String = "7aae7xXr8rnzVqjpYbosS0CFMrlprkD7jbVotm0fd+w=",
)

public interface TpmConfig

public data class BuildConfig(
    val productId: String,
    val productVersion: String,
    val clientName: String,
    val storageConfig: StorageConfig,
    val tpmConfig: TpmConfig,
    val authConfig: AuthConfig,
    val clientSelfAssessment: ClientSelfAssessment,
    val httpClientBuilder: ZetaHttpClientBuilder? = null,
    val registrationCallback: RegistrationCallback? = null,
    val authenticationCallback: AuthenticationCallback? = null,
)

public data class RegInfo(val clientName: String)
public data class AuthInfo(val otp: String? = null)
public fun interface RegistrationCallback { suspend fun registrationCb(): RegInfo }
public fun interface AuthenticationCallback { suspend fun authenticationCb(): AuthInfo }
