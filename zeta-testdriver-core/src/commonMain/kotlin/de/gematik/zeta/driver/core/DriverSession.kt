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

package de.gematik.zeta.driver.core

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

public data class DriverConfig(
    val resource: String,
    val disableTlsVerification: Boolean,
    val caCertificatePem: String = "",
)

public interface ConfigurableDriver {
    public suspend fun configure(request: ConfigureRequest)
    public suspend fun reset()
}

public class DriverSession(
    initial: DriverConfig,
    private val sdkFactory: (DriverConfig) -> ZetaSdkClient,
) : ConfigurableDriver {
    public var config: DriverConfig = initial; private set
    public var sdk: ZetaSdkClient = sdkFactory(initial); private set
    public var httpClient: ZetaHttpClient = buildClient(); private set

    override suspend fun configure(request: ConfigureRequest) {
        configure(DriverConfig(request.resource, request.disableTlsVerification, request.caCertificatePem))
    }

    public fun configure(newConfig: DriverConfig) {
        config = newConfig
        rebuild()
    }

    override suspend fun reset() {
        runCatching { sdk.forget().getOrThrow() }
            .onFailure { Log.w(it) { "forget() failed during reset, continuing" } }
        rebuild()
    }

    private fun rebuild() {
        httpClient.close()
        sdk = sdkFactory(config)
        httpClient = buildClient()
    }

    private fun buildClient(): ZetaHttpClient = sdk.httpClient {
        addCaPem(config.caCertificatePem)
        logging(LogLevel.ALL)
        disableServerValidation(config.disableTlsVerification)
    }
}

public fun Route.sessionRoutes(prefix: String, driver: ConfigurableDriver) {
    route(prefix) {
        get("/reset") {
            runCatching { driver.reset() }.fold(
                onSuccess = { call.respondText("Test driver reset successfully") },
                onFailure = { e -> call.respond(HttpStatusCode.InternalServerError, "Failed to reset TestDriver: ${e.message}") },
            )
        }
        post("/configure") {
            runCatching { driver.configure(call.receive<ConfigureRequest>()) }.fold(
                onSuccess = { call.respondText("Test driver configured successfully", ContentType.Text.Plain) },
                onFailure = { e -> call.respond(HttpStatusCode.BadRequest, "Failed to configure TestDriver: ${e.message}") },
            )
        }
    }
}

@Serializable
public data class ConfigureRequest(
    val caCertificatePem: String,
    val resource: String,
    val disableTlsVerification: Boolean,
)
