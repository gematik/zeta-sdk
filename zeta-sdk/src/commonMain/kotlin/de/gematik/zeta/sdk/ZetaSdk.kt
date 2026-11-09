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

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdk.clearRegistration
import de.gematik.zeta.sdk.asl.AslApi
import de.gematik.zeta.sdk.asl.AslApiImpl
import de.gematik.zeta.sdk.asl.InnerHttpCodecImpl
import de.gematik.zeta.sdk.asl.aslDecryptionPlugin
import de.gematik.zeta.sdk.attestation.AttestationStateStorage
import de.gematik.zeta.sdk.attestation.DeviceAttestor
import de.gematik.zeta.sdk.attestation.assertion.HardwareAttestation
import de.gematik.zeta.sdk.attestation.platformHardwareAttestation
import de.gematik.zeta.sdk.authentication.AccessTokenParams
import de.gematik.zeta.sdk.authentication.AccessTokenProviderImpl
import de.gematik.zeta.sdk.authentication.AuthenticationApiImpl
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.authentication.DpopProviderImpl
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.authentication.identity.ClientManagement
import de.gematik.zeta.sdk.authentication.identity.ClientManagementApiClientImpl
import de.gematik.zeta.sdk.authentication.identity.ClientManagementTokenEndpoints
import de.gematik.zeta.sdk.authentication.identity.ClientManagementTokenProvider
import de.gematik.zeta.sdk.authentication.identity.models.ClientManagementScopes
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.configuration.ConfigurationApiImpl
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContextImpl
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.FlowOrchestrator
import de.gematik.zeta.sdk.flow.ForwardingClient
import de.gematik.zeta.sdk.flow.handler.AslHandler
import de.gematik.zeta.sdk.flow.handler.ClientRegistrationHandler
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler
import de.gematik.zeta.sdk.flow.handler.EnsureAccessTokenHandler
import de.gematik.zeta.sdk.flow.handler.RetryHandler
import de.gematik.zeta.sdk.flow.omitsApplePlatformProductIdInSoftwarePosture
import de.gematik.zeta.sdk.flow.zetaPlugin
import de.gematik.zeta.sdk.network.http.client.CompositeCookieStorage
import de.gematik.zeta.sdk.network.http.client.DEFAULT_REVOCATION_CACHE_SECONDS
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.SdkCookieStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.notifications.LocalPusherStorageImpl
import de.gematik.zeta.sdk.notifications.NotificationApiClientImpl
import de.gematik.zeta.sdk.notifications.NotificationClient
import de.gematik.zeta.sdk.notifications.NotificationClientImpl
import de.gematik.zeta.sdk.notifications.NotificationTokenEndpoints
import de.gematik.zeta.sdk.notifications.PushKeyChainStorageImpl
import de.gematik.zeta.sdk.notifications.ReauthNotificationTokenProvider
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import de.gematik.zeta.sdk.storage.provideSdkStorage
import de.gematik.zeta.sdk.tpm.ClientKeyPoPSigner
import de.gematik.zeta.sdk.tpm.TpmProvider
import de.gematik.zeta.sdk.tpm.platformDefaultProvider
import de.gematik.zeta.time.SystemZetaClock
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.util.appendAll
import kotlinx.coroutines.coroutineScope
import kotlin.jvm.JvmOverloads
import kotlin.time.measureTimedValue

private const val AUTH_SERVER_METADATA_UNAVAILABLE =
    "Authorization Server metadata unavailable - discovery did not run"

/** Client-management API lives on the PEP host of the guard: `https://{guardHost}/zeta`. */
private const val CLIENT_MANAGEMENT_BASE_PATH = "/zeta"

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

object ZetaSdk {
    fun getVersion(): String = ZETA_SDK_VERSION

    @JvmOverloads
    fun build(
        resource: String,
        config: BuildConfig,
        clock: ZetaClock = SystemZetaClock,
    ): ZetaSdkClient {
        val resourceScope = ResourceScope(resource, config.authConfig.scopes)
        val isolatedConfig = config.withNamespace(resourceScope.storageKey)
        return ZetaSdkClientImpl(resourceScope, isolatedConfig, clock)
    }

    suspend fun ZetaSdkClient.forget(): Result<Unit> = runCatching {
        when (this) {
            is ZetaSdkClientImpl -> {
                flowContext.authenticationStorage.clear()
                flowContext.configurationStorage.clear()
                flowContext.clientRegistrationStorage.clear()
                flowContext.tpmStorage.clear()
                tpmProvider.forget()
                deviceAttestor?.forget()
                attestationState.clearAll()
                flowContext.aslStorage.clear()
                sdkCookieStorage.clearCookie()
                flowContext.revocationStorage.clear()
                localPusherStorage.clear()
                pushKeyChainStorage.clear()
                clientManagementAuthStorage.clear()
            }

            else -> this.logout().getOrThrow()
        }
    }

    suspend fun ZetaSdkClient.clearRegistration(): Result<Unit> = runCatching {
        when (this) {
            is ZetaSdkClientImpl -> {
                flowContext.authenticationStorage.clear()
                flowContext.configurationStorage.clear()
                flowContext.clientRegistrationStorage.clear()
                // the default TPM provider uses tpmStorage to store the DpopKeys
                // Thus no need to clear the TPM (which would clear the instance key as well)
                flowContext.tpmStorage.deleteAllDpopKeys()
                flowContext.aslStorage.clear()
                sdkCookieStorage.clearCookie()
                clientManagementAuthStorage.clear()
            }

            else -> this.logout().getOrThrow()
        }
    }
}

class ZetaSdkClientImpl(
    val resourceScope: ResourceScope,
    private val cfg: BuildConfig,
    private val clock: ZetaClock,
) : ZetaSdkClient {
    private lateinit var mainHttpClient: ZetaHttpClient

    private val storage: SdkStorage = when (val storageConfig = cfg.storageConfig) {
        is StorageConfig.Default -> provideSdkStorage(storageConfig)
        is StorageConfig.Custom -> storageConfig.provider
    }

    val sdkCookieStorage = SdkCookieStorage(storage, resourceScope)
    private val compositeCookieStorage = CompositeCookieStorage(sdkCookieStorage)

    /**
     * Pusher identity and its push encryption key material. Held here rather than inside the
     * [notificationClient] lazy so [ZetaSdk.forget] can wipe them without instantiating the whole
     * notification stack, and so they are wiped even when notifications are currently disabled but
     * were enabled on an earlier run. The two always change together (see [de.gematik.zeta.sdk.notifications.PushKeyChainStorage]).
     */
    internal val localPusherStorage = LocalPusherStorageImpl(storage, resourceScope)
    internal val pushKeyChainStorage = PushKeyChainStorageImpl(storage, resourceScope)

    private val forwardingClient = ForwardingClient { builder ->
        mainHttpClient.request {
            takeFrom(builder)
        }
    }

    val flowContext = FlowContextImpl(
        resourceScope = resourceScope,
        client = forwardingClient,
        storage = storage,
        clock = clock,
    )

    internal val attestationState = AttestationStateStorage(storage, resourceScope)

    init {
        cfg.logger?.let { Log.setLogger(it) }
    }

    private val hardwareAttestation: HardwareAttestation? = platformHardwareAttestation(attestationState)

    internal val omitApplePlatformProductIdInSoftwarePosture: suspend () -> Boolean = {
        flowContext.configurationStorage.getAuthServer()
            ?.let { omitsApplePlatformProductIdInSoftwarePosture(it.apiVersionsSupported) }
            ?: false
    }

    internal val deviceAttestor: DeviceAttestor? = hardwareAttestation?.attestor

    private val baseHttpClientBuilder: ZetaHttpClientBuilder =
        (cfg.httpClientBuilder ?: ZetaHttpClientBuilder())
            .copy(
                baseUrl = resourceScope.fqdn,
                cookieStorage = compositeCookieStorage,
            )
            .clock(clock)

    private val revocationHttpClient: ZetaHttpClient =
        baseHttpClientBuilder
            .copy()
            .disableServerValidation(true)
            .build()

    private val revocationChecker = RevocationChecker(
        httpClient = revocationHttpClient,
        storage = flowContext.revocationStorage,
        cacheDurationSeconds = cfg.httpClientBuilder
            ?.revocationCacheDurationSeconds
            ?: DEFAULT_REVOCATION_CACHE_SECONDS,
        clock = clock,
    )

    private val httpClientBuilder: ZetaHttpClientBuilder =
        baseHttpClientBuilder
            .copy()
            .revocationChecker(revocationChecker)

    init {
        Log.i { "[SDK-INIT] ZETA SDK version ${ZetaSdk.getVersion()}" }
        (cfg.authConfig.subjectTokenProvider as? OidcTokenProvider)?.let { oidc ->
            oidc.httpClientBuilder = httpClientBuilder
            OidcEndpointResolver(
                getAuthServer = { flowContext.configurationStorage.getAuthServer() },
            ).resolve(oidc)
        }
    }

    private var clientRegistrationApiClient: ZetaHttpClient? = null
    private var authApiClient: ZetaHttpClient? = null
    private var aslApiClient: ZetaHttpClient? = null

    private val configHandler: ConfigurationHandler by lazy {
        ConfigurationHandler(ConfigurationApiImpl(httpClientBuilder))
    }
    val tpmProvider: TpmProvider = platformDefaultProvider(flowContext.tpmStorage, isHardwareAttestationSupported = deviceAttestor?.isSupported() == true)
    private val clientRegistrationHandler: ClientRegistrationHandler by lazy {
        val redirectUris = (cfg.authConfig.subjectTokenProvider as? OidcTokenProvider)
            ?.config
            ?.let { listOf(it.redirectUri, it.oidcRedirectUri) }
            ?: emptyList()

        ClientRegistrationHandler(
            cfg.clientName,
            ClientRegistrationApiImpl(httpClientBuilder.build().also { clientRegistrationApiClient = it }),
            tpmProvider,
            redirectUris = redirectUris,
            deviceAttestor = deviceAttestor,
            attestationState = attestationState,
            popSigner = tpmProvider as? ClientKeyPoPSigner,
        )
    }

    private lateinit var accessTokenProvider: AccessTokenProviderImpl
    private lateinit var authApi: AuthenticationApiImpl
    private val authHandler: EnsureAccessTokenHandler by lazy {
        authApi = AuthenticationApiImpl(httpClientBuilder.build().also { authApiClient = it }, clock = clock)
        accessTokenProvider = AccessTokenProviderImpl(
            resourceScope.storageKey,
            cfg.authConfig,
            authApi,
            flowContext.authenticationStorage,
            { clock.now().epochSeconds },
            tpmProvider,
            hardwareAttestation?.postureBuilder,
            attestationState,
            omitApplePlatformProductIdInSoftwarePosture,
        )
        EnsureAccessTokenHandler(
            accessTokenProvider,
            tpmProvider,
            authConfig = cfg.authConfig,
            cfg.productId,
            cfg.productVersion,
            cfg.platformProductId,
            clientRegistrationHandler,
        )
    }
    private lateinit var aslApi: AslApi
    private val aslHandler: AslHandler by lazy {
        aslApi = AslApiImpl(
            cfg.authConfig.aslProdEnvironment,
            cfg.authConfig.requiredRoleOid,
            flowContext.aslStorage,
            revocationChecker,
            httpClientBuilder.build().also { aslApiClient = it },
            accessTokenProvider,
            tpmProvider,
            !httpClientBuilder.isServerValidationDisabled,
            clock = clock,
        )

        AslHandler(aslApi)
    }

    private var notificationHttpClient: ZetaHttpClient? = null

    /**
     * Client for the Notification Service co-deployed with this instance's guard. Lazy and
     * memoized: initialization performs no I/O; NS discovery and token acquisition happen inside
     * the first operation call. Exposed to consumers through the platform-gated
     * `ZetaSdkClient.notifications()` extensions (Android/iOS) — push notifications do not
     * exist on the other platforms.
     */
    internal val notificationClient: NotificationClient? by lazy {
        // Notifications are opt-in: a null config disables them entirely (no client wired).
        val nc = cfg.notificationConfig ?: return@lazy null

        // Touching authHandler assigns accessTokenProvider and authApi (both lateinit).
        authHandler

        val nsBaseUrl = serviceBaseUrl(resourceScope.fqdn, nc.apiBasePath)
        val nsHttpClient = httpClientBuilder
            .copy(baseUrl = nsBaseUrl, cookieStorage = sdkCookieStorage)
            .build() // plain build: no zetaPlugin/ASL plugin — auth headers are set per request, like ws()
            .also { notificationHttpClient = it }

        val discovery = NotificationDiscovery(
            configurationApi = ConfigurationApiImpl(httpClientBuilder),
            configurationStorage = flowContext.configurationStorage,
            config = nc,
            resourceFqdn = resourceScope.fqdn,
        )

        val tokenProvider = ReauthNotificationTokenProvider(
            issuerFactory = { store ->
                AccessTokenProviderImpl(
                    resourceScope.storageKey,
                    cfg.authConfig,
                    authApi,
                    store,
                    { clock.now().epochSeconds },
                    tpmProvider,
                    hardwareAttestation?.postureBuilder,
                    attestationState,
                    omitApplePlatformProductIdInSoftwarePosture,
                )
            },
            endpoints = {
                val authServer = requireNotNull(flowContext.configurationStorage.getAuthServer()) {
                    AUTH_SERVER_METADATA_UNAVAILABLE
                }
                NotificationTokenEndpoints(authServer.tokenEndpoint, authServer.nonceEndpoint)
            },
            baseParams = { buildServiceAccessTokenParams() },
            dpopKid = { tpmProvider.generateDpopKey().jwk.kid },
            stepUp = { authHandler.getValidAccessTokenWithStepUp(flowContext) },
            resolveRequest = {
                discover().getOrThrow()
                register().getOrThrow()
                discovery.ensureDiscovered()
            },
        )

        NotificationClientImpl(
            api = NotificationApiClientImpl(
                httpClient = nsHttpClient,
                serviceBaseUrl = nsBaseUrl,
                tokenProvider = tokenProvider,
                dpopProvider = DpopProviderImpl(accessTokenProvider, tpmProvider),
                rateLimitRetryPolicy = nc.rateLimitRetryPolicy,
            ),
            localPusherStorage = localPusherStorage,
            keyChainStorage = pushKeyChainStorage,
        )
    }

    /** The NS lives on the guard host by definition (§4.2): resource host + configured API prefix. */
    internal fun serviceBaseUrl(resourceFqdn: String, apiBasePath: String): String {
        val url = Url(resourceFqdn)
        val defaultPort = when (url.protocol.name.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> 0
        }
        val hostPart = if (':' in url.host) "[${url.host}]" else url.host
        val portPart = if (url.port > 0 && url.port != defaultPort) ":${url.port}" else ""
        return "https://$hostPart$portPart/${apiBasePath.trim('/')}"
    }

    /**
     * Base parameters for the NS token issuance, mirroring the flow-controller's
     * [EnsureAccessTokenHandler] parameter assembly; `scopes`/`audience` are placeholders that
     * [ReauthNotificationTokenProvider] overrides per [NotificationTokenRequest][de.gematik.zeta.sdk.notifications.NotificationTokenRequest].
     */
    internal suspend fun buildServiceAccessTokenParams(): AccessTokenParams {
        val authServer = requireNotNull(flowContext.configurationStorage.getAuthServer()) {
            AUTH_SERVER_METADATA_UNAVAILABLE
        }
        val regKey = authServer.registrationEndpoint?.takeIf { it.isNotBlank() } ?: authServer.issuer
        val clientId = flowContext.clientRegistrationStorage.getClientId(regKey)
        require(!clientId.isNullOrBlank()) { "Client not registered - registration did not run" }
        return AccessTokenParams(
            clientId = clientId,
            productId = cfg.productId,
            productVersion = cfg.productVersion,
            expiration = cfg.authConfig.exp,
            scopes = emptyList(),
            audience = "",
            platformProductId = cfg.platformProductId,
        )
    }

    private fun newOrchestrator(): FlowOrchestrator =
        FlowOrchestrator(
            handlers = listOf(
                configHandler,
                clientRegistrationHandler,
                authHandler,
                aslHandler,
                RetryHandler(),
            ),
        )

    internal val clientManagementAuthStorage = AuthenticationStorageImpl(storage, ResourceScope(resourceScope.fqdn, listOf(ClientManagementScopes.CLIENT_MANAGEMENT)))
    private var clientManagementHttpClient: ZetaHttpClient? = null
    internal val clientManagementTokens: ClientManagementTokenProvider by lazy {
        ClientManagementTokenProvider(
            issuerFactory = { store ->
                AccessTokenProviderImpl(
                    resourceScope.storageKey,
                    cfg.authConfig,
                    authApi,
                    store,
                    { clock.now().epochSeconds },
                    tpmProvider,
                    hardwareAttestation?.postureBuilder,
                    attestationState,
                    omitApplePlatformProductIdInSoftwarePosture,
                )
            },
            persistentStorage = clientManagementAuthStorage,
            endpoints = {
                val authServer = requireNotNull(flowContext.configurationStorage.getAuthServer()) {
                    AUTH_SERVER_METADATA_UNAVAILABLE
                }
                ClientManagementTokenEndpoints(authServer.tokenEndpoint, authServer.nonceEndpoint)
            },
            baseParams = { buildServiceAccessTokenParams() },
            dpopKid = { tpmProvider.generateDpopKey().jwk.kid },
            stepUp = { authHandler.getValidAccessTokenWithStepUp(flowContext) },
            audience = { serviceBaseUrl(resourceScope.fqdn, "") },
        )
    }

    internal val clientManagementBaseUrl: String get() = serviceBaseUrl(resourceScope.fqdn, CLIENT_MANAGEMENT_BASE_PATH)

    internal val clientManagement: ClientManagement by lazy {
        authHandler

        val httpClient = httpClientBuilder
            .copy(cookieStorage = sdkCookieStorage)
            .build()
            .also { clientManagementHttpClient = it }

        ClientManagement(
            api = ClientManagementApiClientImpl(
                httpClient = httpClient,
                baseUrl = clientManagementBaseUrl,
                tokenProvider = clientManagementTokens,
                dpopProvider = DpopProviderImpl(accessTokenProvider, tpmProvider),
            ),
        )
    }

    private val clientManagementOps by lazy {
        ClientManagementOperations(
            discover = { discover().getOrThrow() },
            isRegistered = { canUseClientManagement() },
            currentClientId = { currentClientId() },
            clearRegistration = { clearRegistration().getOrThrow() },
            clientManagement = { clientManagement },
        )
    }

    override suspend fun discover(): Result<Unit> = runCatching {
        val (result, time) = measureTimedValue {
            Log.i { "[SDK-DISCOVER] start" }
            configHandler.handle(FlowNeed.ConfigurationFiles, flowContext)
                .also { Log.i { "[SDK-DISCOVER] end" } }
        }
        Log.i { "[SDK-TIMING] discover (configHandler)=$time result=$result" }
        result.orThrow()
    }

    override suspend fun register(): Result<Unit> = runCatching {
        val (result, time) = measureTimedValue {
            clientRegistrationHandler.handle(FlowNeed.ClientRegistration, flowContext)
        }
        Log.i { "[SDK-TIMING] register (clientRegistrationHandler)=$time result=$result" }
        result.orThrow()
    }

    override suspend fun authenticate(): Result<Unit> = runCatching {
        val (result, time) = measureTimedValue {
            authHandler.handle(FlowNeed.Authentication, flowContext)
        }
        Log.i { "[SDK-TIMING] authenticate (authHandler)=$time result=$result" }
        result.orThrow()
    }

    /**
     * Fail the surrounding [runCatching] when a handler reports a failure, so the
     * returned [Result] reflects the outcome instead of silently succeeding.
     */
    private fun CapabilityResult.orThrow() {
        if (this is CapabilityResult.Error) {
            error("[$internalCode] $internalMessage")
        }
    }

    override fun httpClient(builder: ZetaHttpClientBuilder.() -> Unit): ZetaHttpClient {
        val orchestrator = newOrchestrator()
        mainHttpClient = httpClientBuilder
            .copy(cookieStorage = sdkCookieStorage)
            .apply(builder)
            .build(addExtras = {
                install(aslDecryptionPlugin(aslApi, InnerHttpCodecImpl()))
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

        val (token, dpopKey) = authHandler.getValidAccessTokenWithStepUp(flowContext)
        require(token.isNotBlank())

        val hashedToken = accessTokenProvider.hash(token)
        val dpop = accessTokenProvider.createDpopToken(dpopKey.jwk, "GET", targetUrl, null, hashedToken)

        val wsClient = httpClientBuilder
            .copy(cookieStorage = sdkCookieStorage)
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

    override suspend fun status(): Result<SdkStatus> = runCatching {
        if (!isRegistered()) return@runCatching SdkStatus.NOT_REGISTERED

        val storage = flowContext.authenticationStorage

        val hasValidAccessToken = !storage.getAccessToken().isNullOrBlank() &&
            !isExpired(storage.getAccessTokenExpiration(), unknownIsExpired = true)

        val hasUsableRefreshToken = !storage.getRefreshToken().isNullOrBlank() &&
            !isExpired(storage.getRefreshTokenExpiration(), unknownIsExpired = false)

        when {
            hasValidAccessToken && hasUsableRefreshToken -> SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN
            hasUsableRefreshToken -> SdkStatus.HAS_REFRESH_TOKEN
            else -> SdkStatus.REGISTERED_NO_VALID_TOKENS
        }
    }

    private suspend fun registrationKey(): String? {
        val authServer = flowContext.configurationStorage.getAuthServer() ?: return null
        return authServer.registrationEndpoint?.takeIf { it.isNotBlank() } ?: authServer.issuer
    }

    // Unchanged semantics, used by status() and client management
    private suspend fun isRegistered(): Boolean {
        val regKey = registrationKey() ?: return false
        return flowContext.clientRegistrationStorage.getRegistrationInfo(regKey) != null
    }

    private suspend fun currentClientId(): String? {
        val regKey = registrationKey() ?: return null
        return flowContext.clientRegistrationStorage.getClientId(regKey)
    }

    private suspend fun canUseClientManagement(): Boolean =
        isRegistered() && !currentClientId().isNullOrBlank()

    private fun isExpired(epochSeconds: String?, unknownIsExpired: Boolean): Boolean {
        val expiresAt = epochSeconds?.toLongOrNull() ?: return unknownIsExpired
        return expiresAt <= clock.now().epochSeconds
    }

    override suspend fun logout(): Result<Unit> = runCatching {
        flowContext.authenticationStorage.clear()
        flowContext.tpmStorage.deleteAllDpopKeys()
        sdkCookieStorage.clearCookie()
        clientManagementAuthStorage.clear()
    }

    override suspend fun close(): Result<Unit> = runCatching {
        if (::mainHttpClient.isInitialized) mainHttpClient.close()
        clientRegistrationApiClient?.close()
        authApiClient?.close()
        aslApiClient?.close()
        notificationHttpClient?.close()
        sdkCookieStorage.clearCookie()
        clientManagementHttpClient?.close()
    }

    override suspend fun changeEmail(newEmail: String) = runCatching { clientManagementOps.changeEmail(newEmail) }
    override suspend fun resendEmailChangeCode() = runCatching { clientManagementOps.resendEmailChangeCode() }
    override suspend fun verifyEmailChange(code: String) = runCatching { clientManagementOps.verifyEmailChange(code) }
    override suspend fun listClients() = runCatching { clientManagementOps.listClients() }
    override suspend fun deleteClient(id: String) = runCatching { clientManagementOps.deleteClient(id) }
}

internal class OidcEndpointResolver(
    private val getAuthServer: suspend () -> AuthorizationServerMetadata?,
) {
    fun resolve(oidc: OidcTokenProvider) {
        val resolveIssuer: suspend () -> String = {
            getAuthServer()?.issuer ?: error("Discovery not completed, issuer unavailable")
        }

        oidc.resolveParEndpoint = { "${resolveIssuer()}/protocol/openid-connect/ext/par/request" }
        oidc.resolveBindEmailEndpoint = { "${resolveIssuer()}/zeta/identity/bind-email" }
        oidc.resolveVerifyEmailEndpoint = { "${resolveIssuer()}/zeta/identity/bind-email/verify" }
        oidc.resolveResendEmailEndpoint = { "${resolveIssuer()}/zeta/identity/bind-email/resend" }
        oidc.resolveAuthorizationEndpoint = {
            getAuthServer()?.authorizationEndpoint ?: error("authorizationEndpoint not available from discovery")
        }
        oidc.resolveBrokerEndpoint = { "${resolveIssuer()}/broker/${oidc.config.idpAlias}/endpoint" }
    }
}
