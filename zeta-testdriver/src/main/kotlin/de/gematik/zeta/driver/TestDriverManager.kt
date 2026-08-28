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

import de.gematik.zeta.driver.model.ConfigureRequest
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.driver.model.toKtorLogLevel
import de.gematik.zeta.driver.oidc.TestDriverOtpCallback
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.InternalZetaApi
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.notifications.NotificationClient
import de.gematik.zeta.sdk.notificationsForTesting
import de.gematik.zeta.sdk.storage.InMemoryStorage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Manages the test driver SDK client and configuration.
 * Separated for better testability.
 */
public open class TestDriverManager(
    initialConfig: SdkInstanceConfig = SdkInstanceConfig.fromFileOrEnv(),
) {
    private val store = InMemoryStorage()
    public val otpCallback: TestDriverOtpCallback = TestDriverOtpCallback(initialConfig.oidcBindingEmail)
    public var config: SdkInstanceConfig = initialConfig
        private set

    public var sdk: ZetaSdkClient = createSdk()
        private set

    public var httpClient: ZetaHttpClient = createHttpClient()
        private set

    public open fun configure(request: ConfigureRequest) {
        config = config.copy(
            disableTlsVerification = request.disableTlsVerification,
            fachdienstUrl = request.resource.takeIf { it.isNotEmpty() } ?: config.fachdienstUrl,
        )

        if (request.caCertificatePem.isNotEmpty()) {
            customCaPems.clear()
            customCaPems.add(request.caCertificatePem)
        }

        rebuildClient()
    }

    public open fun setKvnrEmail(kvnr: String, email: String) {
        config = config.copy(oidcTestKvnr = kvnr)
        config = config.copy(oidcBindingEmail = email)
        otpCallback.setBoundEmail(email)

        rebuildClient()
    }

    public fun reset(newConfig: SdkInstanceConfig = SdkInstanceConfig.fromFileOrEnv()) {
        config = newConfig
        customCaPems.clear()
        rebuildClient()
    }

    public open fun getStorageSnapshot(): JsonObject {
        return buildJsonObject {
            store.map.forEach { (key, value) ->
                val trimmed = value.trim()
                val element: JsonElement = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
                }.getOrElse { JsonPrimitive(trimmed) }
                put(key, element)
            }

            store.map.entries
                .firstOrNull { it.key.startsWith("dpop_private_key") }
                ?.let { put("dpop_private_key", JsonPrimitive(it.value)) }
            store.map.entries
                .firstOrNull { it.key.startsWith("dpop_public_key") }
                ?.let { put("dpop_public_key", JsonPrimitive(it.value)) }
            store.map.entries
                .firstOrNull { it.key.startsWith("client_private_key") }
                ?.let { put("client_private_key", JsonPrimitive(it.value)) }
            store.map.entries
                .firstOrNull { it.key.startsWith("client_public_key") }
                ?.let { put("client_public_key", JsonPrimitive(it.value)) }
        }
    }

    @OptIn(InternalZetaApi::class)
    public fun notifications(): NotificationClient = sdk.notificationsForTesting()

    public fun getStorage(): InMemoryStorage = store

    private fun rebuildClient() {
        sdk = createSdk()
        httpClient = createHttpClient()
    }

    private fun createSdk(): ZetaSdkClient {
        return newSdk(storage = store, config, otpCallback)
    }

    private fun createHttpClient(): ZetaHttpClient {
        return sdk.httpClient {
            logging(Log.logLevel.toKtorLogLevel())
            disableServerValidation(config.disableTlsVerification)
            customCaPems.forEach { pem -> addCaPem(pem) }
        }
    }
}
