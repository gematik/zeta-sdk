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

import de.gematik.zeta.logging.ZetaLogger
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession

interface ZetaSdkClient {
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
    suspend fun status(): Result<SdkStatus>
    suspend fun logout(): Result<Unit>
    suspend fun close(): Result<Unit>
    suspend fun changeEmail(newEmail: String): Result<ChangeEmailResponse>
}

interface TpmConfig

data class BuildConfig(
    val productId: String,
    val productVersion: String,
    val clientName: String,
    val storageConfig: StorageConfig,
    val tpmConfig: TpmConfig,
    val authConfig: AuthConfig,
    val platformProductId: PlatformProductId,
    val httpClientBuilder: ZetaHttpClientBuilder? = null,
    val registrationCallback: RegistrationCallback? = null,
    val logger: ZetaLogger? = null,
    /** Notification Service integration; `null` (the default) disables notifications entirely. */
    val notificationConfig: NotificationConfig? = null,
)

fun BuildConfig.withNamespace(namespace: String): BuildConfig {
    return copy(
        storageConfig = when (val sc = storageConfig) {
            is StorageConfig.Default -> sc.copy(namespace = ExtendedStorage.hash(namespace))
            is StorageConfig.Custom -> sc
        },
    )
}

data class RegInfo(val clientName: String)
fun interface RegistrationCallback { suspend fun registrationCb(): RegInfo }
