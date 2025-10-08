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

import de.gematik.zeta.sdk.authentication.AccessTokenProviderImpl
import de.gematik.zeta.sdk.authentication.AuthenticationApiImpl
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.clientregistration.PostureProviderImpl
import de.gematik.zeta.sdk.configuration.ConfigurationApiImpl
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.FlowOrchestrator
import de.gematik.zeta.sdk.flow.ForwardingClient
import de.gematik.zeta.sdk.flow.handler.ClientRegistrationHandler
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler
import de.gematik.zeta.sdk.flow.handler.EnsureAccessTokenHandler
import de.gematik.zeta.sdk.flow.zetaPlugin
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse

/**
 * Zeta SDK entry point.
 *
 * Provides a DSL to create a configured [HttpClient] in a single call.
 *
 * Usage:
 * ```
 * val client = ZetaSdk.httpClient {
 *   timeouts(connectMs = 2_000, requestMs = 10_000)
 *   retry(setOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.NotFound), maxRetries = 3, onlyIdempotent = true)
 *   logLevel(LogLevel.INFO)
 * }
 * ```
 */

public object ZetaSdk {
    public fun build(
        resource: String,
        config: BuildConfig,
    ): ZetaSdkClient = ZetaSdkClientImpl(resource, config)

    public suspend fun forget(): Result<Unit> = runCatching {
        TODO("")
    }
}

private class ZetaSdkClientImpl(
    private val resource: String,
    private val cfg: BuildConfig,
) : ZetaSdkClient {
    private val baseClient = ZetaHttpClientBuilder(resource).build()
    private val forwardingClient = object : ForwardingClient {
        override suspend fun executeOnce(builder: HttpRequestBuilder): HttpResponse = baseClient.request(builder)
    }
    private val storage: SdkStorage = cfg.storageConfig.provider ?: InMemoryStorage()
    private val flowContext = FlowContext(forwardingClient, storage)
    private val configHandler: ConfigurationHandler by lazy {
        ConfigurationHandler(ConfigurationApiImpl(resource, cfg.authConfig.authUrl))
    }
    private val clientRegistrationHandler: ClientRegistrationHandler by lazy {
        ClientRegistrationHandler(ClientRegistrationApiImpl(cfg.authConfig.authUrl), PostureProviderImpl())
    }

    private val authHandler: EnsureAccessTokenHandler by lazy {
        EnsureAccessTokenHandler(
            AccessTokenProviderImpl(
                AuthenticationApiImpl(cfg.authConfig),
                cfg.authConfig,
                storage,
            ),
        )
    }

    private fun newOrchestrator(): FlowOrchestrator =
        FlowOrchestrator(
            handlers = listOf(
                // TODO: uncomment when the endpoints are available
            /*configHandler,
            clientRegistrationHandler,*/
                authHandler,
            ),
        )

    override suspend fun discover(): Result<Unit> = runCatching {
        configHandler.handle(FlowNeed.ConfigurationFiles, flowContext)
    }.map { }

    override suspend fun register(): Result<Unit> = runCatching {
        clientRegistrationHandler.handle(FlowNeed.ClientRegistration, flowContext)
    }.map {}

    override suspend fun authenticate(): Result<Unit> = runCatching {
        authHandler.handle(FlowNeed.Authentication, flowContext)
    }.map {}

    /**
     * Create and configure an [HttpClient] with the [zetaPlugin] using the [ZetaHttpClientBuilder] DSL.
     * This variant wires the flow-controller into the client pipeline, enabling request/response orchestration
     * such as authentication, service discovery, schema validation,device registration and retries.
     * @param builder configuration lambda executed on a fresh [ZetaHttpClientBuilder].
     * @return A built and configured [HttpClient].
     */
    override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): HttpClient {
        val orchestrator = newOrchestrator()
        return ZetaHttpClientBuilder(resource)
            .apply(builder)
            .build(addExtras = {
                install(zetaPlugin(orchestrator, flowContext))
            })
    }

    override suspend fun close(): Result<Unit> = runCatching {
        TODO("Has to be implemented")
    }
}
