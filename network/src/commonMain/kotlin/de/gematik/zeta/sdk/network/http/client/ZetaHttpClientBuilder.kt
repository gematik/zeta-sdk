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

package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.config.ClientConfig
import de.gematik.zeta.sdk.network.http.client.config.MonitoringConfig
import de.gematik.zeta.sdk.network.http.client.config.NetworkConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import de.gematik.zeta.sdk.network.http.client.config.SecurityConfig
import de.gematik.zeta.time.SystemZetaClock
import de.gematik.zeta.time.ZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.http.HttpStatusCode

/**
 * Fluent builder for constructing a configured [HttpClient] instance for the Zeta SDK.
 *
 * The builder groups configuration into four areas:
 *  - **Base URL**: override the server base URL for all requests.
 *  - **Network**: connection/request timeouts and retry policy.
 *  - **Security**: additional trusted CA certificates (PEM).
 *  - **Monitoring**: client log verbosity.
 *
 * Defaults come from [NetworkConfig], [SecurityConfig], and [MonitoringConfig].
 *
 * Typical usage:
 * ```
 * return ZetaHttpClientBuilder()
 *      .baseUrl("https://zeta-dev.testsystem.de")
 *      .timeouts(connectMs = 5_000, requestMs = 15_000)
 *      .retry(
 *          statusCodes = setOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable),
 *          maxRetries = 3,
 *          onlyIdempotent = true
 *      )
 *      .addCaPem("-----BEGIN CERTIFICATE-----...-----END CERTIFICATE-----")
 *      .logging(LogLevel.INFO)
 *      .build()
 * ```
 *
 * Thread-safety: the builder is mutable and not thread-safe. Build once and share the resulting [HttpClient].
 */
public open class ZetaHttpClientBuilder(
    private val baseUrl: String = "",
    private val cookieStorage: CookiesStorage = AcceptAllCookiesStorage(),
) {
    private var network: NetworkConfig = NetworkConfig()
    private var security: SecurityConfig = SecurityConfig()
    private var monitoring: MonitoringConfig = MonitoringConfig()
    private var contentNegotiationEnabled: Boolean = false
    private var revocationChecker: RevocationChecker? = null
    private var clock: ZetaClock = SystemZetaClock

    /**
     * Configure connection and/or overall request timeouts (milliseconds).
     *
     * Passing `null` for a parameter keeps the current value (previous call or default).
     *
     * @param connectMs Connection timeout in milliseconds (TCP/TLS handshake). `null` = leave unchanged.
     * @param requestMs Overall request timeout in milliseconds (entire call). `null` = leave unchanged.
     * @param socketMs Overall socket timeout in milliseconds. `null` = leave unchanged.
     * @return This builder for chaining.
     */
    public fun timeouts(connectMs: Long? = null, requestMs: Long? = null, socketMs: Long? = null): ZetaHttpClientBuilder = apply {
        network = network.copy(
            connectionTimeoutMillis = connectMs ?: network.connectionTimeoutMillis,
            requestTimeoutMillis = requestMs ?: network.requestTimeoutMillis,
            socketTimeoutMillis = socketMs ?: network.socketTimeoutMillis,
        )
        validateTimeouts(network)
    }

    private fun validateTimeouts(network: NetworkConfig) {
        if (network.socketTimeoutMillis < network.requestTimeoutMillis) {
            Log.w {
                "ZetaHttpClientBuilder: socketTimeoutMillis (${network.socketTimeoutMillis}ms) is lower than " +
                    "requestTimeoutMillis (${network.requestTimeoutMillis}ms)"
            }
        }
    }

    /**
     * Configure automatic retry behavior for responses with specific HTTP status codes.
     *
     * @param statusCodes Set of HTTP status codes that should trigger a retry.
     *                    Defaults to the current value in [NetworkConfig].
     * @param maxRetries Maximum number of retry attempts (excluding the initial attempt).
     *                   Defaults to the current value in [NetworkConfig].
     * @param onlyIdempotent When `true`, retries are limited to idempotent HTTP methods
     *                       (e.g., GET/HEAD/PUT/DELETE). Semantics depend on the underlying client.
     * @return This builder for chaining.
     */
    public fun retry(
        statusCodes: Set<HttpStatusCode> = network.retryStatusCodes,
        maxRetries: Int = network.maxRetries,
        onlyIdempotent: Boolean = network.retryOnlyIdempotent,
    ): ZetaHttpClientBuilder = apply {
        network = network.copy(
            retryStatusCodes = statusCodes,
            maxRetries = maxRetries,
            retryOnlyIdempotent = onlyIdempotent,
        )
    }

    /**
     * Disable server certificate and hostname validation
     *
     * NOTE: for testing only!
     *
     * @return This builder for chaining
     */
    public fun disableServerValidation(disableServerValidation: Boolean): ZetaHttpClientBuilder = apply {
        security = security.copy(disableServerValidation = disableServerValidation)
    }

    /**
     * Append an additional trusted Certificate Authority (CA) in PEM format.
     *
     * Use this when your server is signed by a private/internal CA not present in
     * the default trust store.
     *
     * @param pem String containing a single PEM-encoded certificate (including
     *            `-----BEGIN CERTIFICATE-----` / `-----END CERTIFICATE-----`).
     * @return This builder for chaining.
     */
    public fun addCaPem(pem: String): ZetaHttpClientBuilder = apply {
        security = security.copy(additionalCaPem = security.additionalCaPem + pem)
    }

    public fun addCaPemFile(pemPath: String): ZetaHttpClientBuilder = apply {
        security = security.copy(additionalCaFile = pemPath)
    }

    /**
     * Replace the entire list of additional trusted CAs.
     *
     * @param pems List of PEM-encoded certificates (each as a full PEM string).
     * @return This builder for chaining.
     */
    public fun setCaPems(pems: List<String>): ZetaHttpClientBuilder = apply {
        security = security.copy(additionalCaPem = pems.toList())
    }

    /**
     * Configure the maximum duration for caching OCSP/CRL revocation responses.
     *
     * A cached response is used while now() is before min(nextUpdate, validatedAt + seconds).
     *
     * @param seconds Maximum cache duration in seconds. Defaults to 3600s (1h).
     * @return This builder for chaining.
     */
    public fun revocationCacheDuration(seconds: Long): ZetaHttpClientBuilder = apply {
        security = security.copy(revocationCacheDurationSeconds = seconds)
    }

    /**
     * Set the client log verbosity used for request/response monitoring.
     *
     * @param level Desired [LogLevel] (e.g., [LogLevel.NONE], [LogLevel.INFO], [LogLevel.HEADERS], [LogLevel.BODY]).
     * @return This builder for chaining.
     */
    public fun logging(level: LogLevel = LogLevel.ALL): ZetaHttpClientBuilder = apply {
        monitoring = monitoring.copy(logLevel = level)
    }

    internal fun logging(level: LogLevel, logProvider: Logger): ZetaHttpClientBuilder = apply {
        monitoring = monitoring.copy(logLevel = level, logProvider = logProvider)
    }

    /**
     * Set client proxy.
     *
     * @param proxyConfig Desired [ProxyConfig].
     * @return This builder for chaining.
     */
    public fun proxy(proxyConfig: ProxyConfig): ZetaHttpClientBuilder = apply {
        network = network.copy(proxyConfig = proxyConfig)
    }

    /**
     * Controls whether the [ContentNegotiation] plugin is installed on the HTTP client.
     *
     * When `true`, the client appends `Accept: application/json` to every request
     * and supports automatic response body deserialization via `body<T>()`.
     *
     * Set to `false` (default) when using the client as a raw proxy to avoid polluting forwarded headers.
     *
     * @return This builder for chaining.
     */
    public fun contentNegotiation(enabled: Boolean): ZetaHttpClientBuilder = apply {
        contentNegotiationEnabled = enabled
    }

    public fun revocationChecker(
        checker: RevocationChecker,
    ): ZetaHttpClientBuilder = apply {
        revocationChecker = checker
    }

    public fun clock(
        zetaClock: ZetaClock,
    ): ZetaHttpClientBuilder = apply {
        clock = zetaClock
    }

    /**
     * Build a configured [HttpClient] using the current builder state.
     *
     * Delegates to the internal `zetaHttpClient { ... }` factory to construct and wire the client.
     *
     * @return A ready-to-use [HttpClient] instance.
     */
    public fun build(): ZetaHttpClient =
        zetaHttpClient(
            configure = baseConfig(),
            dependencies = runtimeDependencies(),
        )

    /**
     * Build a configured [HttpClient] using the current builder state.
     *
     * Delegates to the internal `zetaHttpClient { ... }` factory to construct and wire the client.
     * @param addExtras Lambda to add extra custom configuration.
     * @return A ready-to-use [HttpClient] instance.
     */
    public fun build(
        addExtras: (HttpClientConfig<*>.() -> Unit)? = null,
    ): ZetaHttpClient =
        zetaHttpClient(
            configure = baseConfig(),
            dependencies = runtimeDependencies(),
            addExtras = addExtras,
        )

    /**
     * Build a configured [HttpClient] using the current builder state, but with a different baseUrl.
     * Needed for the internal calls to the PDP endpoints.
     *
     * Delegates to the internal `zetaHttpClient { ... }` factory to construct and wire the client.
     * @param newUrl The base URL override for this client instance.
     * @return A ready-to-use [HttpClient] instance.
     */
    public open fun build(newUrl: String): ZetaHttpClient =
        zetaHttpClient(
            configure = baseConfig(newUrl),
            dependencies = runtimeDependencies(),
        )

    /**
     * Build a configured [HttpClient] using the current builder state with an optional engine override.
     *
     * Delegates to the internal `zetaHttpClient { ... }` factory to construct and wire the client.
     * @param engine Optional [HttpClientEngine] to use instead of the platform default.
     * @return A ready-to-use [HttpClient] instance.
     */
    public fun build(engine: HttpClientEngine? = null): ZetaHttpClient =
        zetaHttpClient(
            configure = {
                baseConfig()(this)
                if (engine != null) {
                    this.engine(engine)
                }
            },
            dependencies = runtimeDependencies(),
        )

    private fun runtimeDependencies(): HttpClientDependencies =
        HttpClientDependencies(
            revocationChecker = revocationChecker,
            clock = clock,
        )

    private fun baseConfig(urlOverride: String = baseUrl): ClientConfig.() -> Unit = {
        this.baseUrlOverride = urlOverride
        this.network = this@ZetaHttpClientBuilder.network
        this.security = this@ZetaHttpClientBuilder.security
        this.monitoring = this@ZetaHttpClientBuilder.monitoring
        this.contentNegotiation = this@ZetaHttpClientBuilder.contentNegotiationEnabled
        this.cookieStorage = this@ZetaHttpClientBuilder.cookieStorage
    }

    public fun copy(
        baseUrl: String = this.baseUrl,
        cookieStorage: CookiesStorage = this.cookieStorage,
    ): ZetaHttpClientBuilder =
        ZetaHttpClientBuilder(baseUrl = baseUrl, cookieStorage = cookieStorage)
            .also { copy ->
                copy.network = this.network
                copy.security = this.security
                copy.monitoring = this.monitoring
                copy.contentNegotiationEnabled = this.contentNegotiationEnabled
                copy.revocationChecker = this.revocationChecker
                copy.clock = this.clock
            }

    public val isServerValidationDisabled: Boolean
        get() = security.disableServerValidation

    public val revocationCacheDurationSeconds: Long
        get() = security.revocationCacheDurationSeconds
}
