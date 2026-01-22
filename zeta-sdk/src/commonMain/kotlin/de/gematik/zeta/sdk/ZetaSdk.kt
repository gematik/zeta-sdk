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

import de.gematik.zeta.sdk.asl.AslApi
import de.gematik.zeta.sdk.asl.AslApiImpl
import de.gematik.zeta.sdk.asl.aslDecryptionPlugin
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.AccessTokenProviderImpl
import de.gematik.zeta.sdk.authentication.AuthenticationApiImpl
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.configuration.ConfigurationApiImpl
import de.gematik.zeta.sdk.flow.FlowContextImpl
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.FlowOrchestrator
import de.gematik.zeta.sdk.flow.ForwardingClient
import de.gematik.zeta.sdk.flow.handler.AslHandler
import de.gematik.zeta.sdk.flow.handler.ClientRegistrationHandler
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler
import de.gematik.zeta.sdk.flow.handler.EnsureAccessTokenHandler
import de.gematik.zeta.sdk.flow.zetaPlugin
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.provideSdkStorage
import de.gematik.zeta.sdk.tpm.Tpm
import de.gematik.zeta.sdk.tpm.TpmProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.appendAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock.System

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
    private lateinit var client: ZetaSdkClientImpl
    public fun build(
        resource: String,
        config: BuildConfig,
    ): ZetaSdkClient {
        client = ZetaSdkClientImpl(resource, config)
        return client
    }

    public suspend fun forget(): Result<Unit> = runCatching {
        client.flowContext.authenticationStorage.clear()
        client.flowContext.configurationStorage.clear()
        client.flowContext.clientRegistrationStorage.clear()
        client.flowContext.tpmStorage.clear()
        client.tpmProvider.forget()
        client.flowContext.aslStorage.clear()
    }
}

private class ZetaSdkClientImpl(
    private val resource: String,
    private val cfg: BuildConfig,
) : ZetaSdkClient {
    private lateinit var mainHttpClient: ZetaHttpClient
    private val httpClientBuilder: ZetaHttpClientBuilder = (
        cfg.httpClientBuilder
            ?: ZetaHttpClientBuilder().logging(
                LogLevel.ALL,
                object : Logger {
                    override fun log(message: String) {
                        println(message)
                    }
                },
            )
        )
    private val forwardingClient = object : ForwardingClient {
        override suspend fun executeOnce(builder: HttpRequestBuilder): ZetaHttpResponse = mainHttpClient.request {
            takeFrom(builder)
        }
    }

    private val storage: SdkStorage = cfg.storageConfig.provider ?: provideSdkStorage(cfg.storageConfig.aesB64Key)
    val flowContext = FlowContextImpl(resource, forwardingClient, storage)
    private val configHandler: ConfigurationHandler by lazy {
        ConfigurationHandler(ConfigurationApiImpl(httpClientBuilder), cfg.authConfig)
    }
    val tpmProvider: TpmProvider = Tpm.provider(flowContext.tpmStorage)
    private val clientRegistrationHandler: ClientRegistrationHandler by lazy {
        ClientRegistrationHandler(cfg.clientName, ClientRegistrationApiImpl(httpClientBuilder.build()), tpmProvider)
    }

    private lateinit var accessTokenProvider: AccessTokenProvider
    private val authHandler: EnsureAccessTokenHandler by lazy {
        accessTokenProvider = AccessTokenProviderImpl(
            resource,
            cfg.authConfig,
            AuthenticationApiImpl(httpClientBuilder.build()),
            flowContext.authenticationStorage,
            { System.now().epochSeconds },
            tpmProvider,
        )
        EnsureAccessTokenHandler(
            accessTokenProvider,
            authConfig = cfg.authConfig,
            cfg.productId,
            cfg.productVersion,
            cfg.clientSelfAssessment,
        )
    }
    private lateinit var aslApi: AslApi
    private val aslHandler: AslHandler by lazy {
        aslApi = AslApiImpl(resource, cfg.authConfig.aslProdEnvironment, flowContext.aslStorage, httpClientBuilder.build(), accessTokenProvider)

        AslHandler(aslApi)
    }

    private fun newOrchestrator(): FlowOrchestrator =
        FlowOrchestrator(
            handlers = listOf(
                configHandler,
                clientRegistrationHandler,
                authHandler,
                aslHandler,
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
    override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
        val orchestrator = newOrchestrator()
        mainHttpClient = ZetaHttpClientBuilder(resource)
            .apply(builder)
            .build(addExtras = {
                install(aslDecryptionPlugin(aslApi))
                install(zetaPlugin(orchestrator, flowContext))
            })

        return mainHttpClient
    }

    override suspend fun <R> ws(
        targetUrl: String,
        builder: ZetaHttpClientBuilder.() -> Unit,
        customHeaders: Map<String, String>?,
        block: suspend DefaultClientWebSocketSession.() -> R,
    ) = coroutineScope {
        discover().getOrThrow()
        register().getOrThrow()

        val token = authHandler.getValidAccessToken(flowContext)
        require(token.isNotBlank())

        val hashedToken = accessTokenProvider.hash(token)
        val dpop = accessTokenProvider.createDpopToken("GET", targetUrl, null, hashedToken)

        val wsClient = ZetaHttpClientBuilder(resource)
            .apply(builder)
            .build()

        wsClient.webSocket(request = {
            url(targetUrl)
            header(HttpHeaders.Authorization, "${HttpAuthHeaders.Dpop} $token")
            header(HttpAuthHeaders.Dpop, dpop)
            header(HttpHeaders.Accept, "application/json")
            customHeaders?.let {
                headers.appendAll(customHeaders)
            }
        }) {
            block()
        }
    }

    override suspend fun close(): Result<Unit> = runCatching {
        TODO("Has to be implemented")
    }
}
