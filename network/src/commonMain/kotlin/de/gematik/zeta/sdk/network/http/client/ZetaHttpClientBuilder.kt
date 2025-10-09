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

import de.gematik.zeta.sdk.network.http.client.config.MonitoringConfig
import de.gematik.zeta.sdk.network.http.client.config.NetworkConfig
import de.gematik.zeta.sdk.network.http.client.config.SecurityConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.logging.DEFAULT
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
 *      .baseUrl("https://base-url.de")
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
public class ZetaHttpClientBuilder(private val baseUrl: String) {
    private var network: NetworkConfig = NetworkConfig()
    private var security: SecurityConfig = SecurityConfig()
    private var monitoring: MonitoringConfig = MonitoringConfig()

    /**
     * Configure connection and/or overall request timeouts (milliseconds).
     *
     * Passing `null` for a parameter keeps the current value (previous call or default).
     *
     * @param connectMs Connection timeout in milliseconds (TCP/TLS handshake). `null` = leave unchanged.
     * @param requestMs Overall request timeout in milliseconds (entire call). `null` = leave unchanged.
     * @return This builder for chaining.
     */
    public fun timeouts(connectMs: Long? = null, requestMs: Long? = null): ZetaHttpClientBuilder = apply {
        network = network.copy(
            connectionTimeoutMillis = connectMs ?: network.connectionTimeoutMillis,
            requestTimeoutMillis = requestMs ?: network.requestTimeoutMillis,
        )
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
     * Set the client log verbosity used for request/response monitoring.
     *
     * @param level Desired [LogLevel] (e.g., [LogLevel.NONE], [LogLevel.INFO], [LogLevel.HEADERS], [LogLevel.BODY]).
     * @param logProvider Desired [Logger] ( default: [Logger.Default]).
     * @return This builder for chaining.
     */
    public fun logging(level: LogLevel, logProvider: Logger = Logger.DEFAULT): ZetaHttpClientBuilder = apply {
        monitoring = monitoring.copy(logLevel = level, logProvider = logProvider)
    }

    /**
     * Build a configured [HttpClient] using the current builder state.
     *
     * Delegates to the internal `zetaHttpClient { ... }` factory to construct and wire the client.
     *
     * @return A ready-to-use [HttpClient] instance.
     */
    public fun build(): HttpClient {
        return zetaHttpClient(
            configure = {
                this.baseUrlOverride = baseUrl
                this.network = this@ZetaHttpClientBuilder.network
                this.security = this@ZetaHttpClientBuilder.security
                this.monitoring = this@ZetaHttpClientBuilder.monitoring
            },
        )
    }

    /**
     * Build a configured [HttpClient] using the current builder state.
     *
     * Delegates to the internal `zetaHttpClient { ... }` factory to construct and wire the client.
     * @param addExtras Lambda to add extra custom configuration.
     * @return A ready-to-use [HttpClient] instance.
     */
    public fun build(addExtras: (HttpClientConfig<*>.() -> Unit)? = null): HttpClient {
        return zetaHttpClient(
            configure = {
                this.baseUrlOverride = baseUrl
                this.network = this@ZetaHttpClientBuilder.network
                this.security = this@ZetaHttpClientBuilder.security
                this.monitoring = this@ZetaHttpClientBuilder.monitoring
            },
            addExtras = addExtras,
        )
    }

    /**
     * Build a configured [HttpClient] using the current builder state.
     *
     * Delegates to the internal `zetaHttpClient { ... }` factory to construct and wire the client.
     *
     * @return A ready-to-use [HttpClient] instance.
     */
    public fun build(engine: HttpClientEngine? = null): HttpClient {
        return zetaHttpClient(
            configure = {
                this.baseUrlOverride = baseUrl
                this.network = this@ZetaHttpClientBuilder.network
                this.security = this@ZetaHttpClientBuilder.security
                this.monitoring = this@ZetaHttpClientBuilder.monitoring
                if (engine != null) this.engine(engine)
            },
        )
    }
}
