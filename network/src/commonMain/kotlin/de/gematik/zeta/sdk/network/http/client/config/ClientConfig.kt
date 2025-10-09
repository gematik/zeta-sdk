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

package de.gematik.zeta.sdk.network.http.client.config

import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpMethod

/**
* Aggregates all configurations required to build and initialize the SDK's HTTP client.
*
* This class is **mutable during configuration time** and typically consumed by a higher-level
* builder (e.g., `Client { … }`). After the client is built, instances should be treated as
* effectively immutable and not reused concurrently.
*
* ### Configuration style
* - `baseUrl("https://...")` sets a hard override for the service base URL.
* - `network { … }`, `security { … }`, `monitoring { … }` each receive the **current** config
*   instance and must **return** the updated instance. This supports immutable config types
*   (e.g., data classes using `copy(...)`).
* - `engine { … }` lets you provide a platform-specific `HttpClientEngine` **factory** that will
*   be invoked lazily by the client builder; `engine(engine)` pins a concrete instance.
*
*/
internal class ClientConfig {
    /**
     * Optional hard override for the backend base URL.
     *
     * If `null`, the client builder is expected to fall back to its environment/defaults.
     * No normalization is performed here (e.g., trailing slashes), so callers should pass a
     * canonical value if required by upstream layers.
     */
    lateinit var baseUrlOverride: String

    /**
     * Low-level network settings (timeouts, redirects, proxies, etc.).
     * Initialized to a default instance.
     */
    var network: NetworkConfig = NetworkConfig()

    /**
     * Security settings (TLS, cert pinning, auth, key stores, etc.).
     * Initialized to a default instance.
     */
    var security: SecurityConfig = SecurityConfig()

    /**
     * Observability settings (logging, metrics, tracing, event hooks, etc.).
     * Initialized to a default instance.
     */
    var monitoring: MonitoringConfig = MonitoringConfig()

    /**
     * Replaces the current [network] configuration with the result of [block].
     *
     * The block is invoked with the current config as receiver and **must return**
     * the instance to store (commonly the same receiver or a `copy(...)`).
     *
     * @param block Transformer that returns the new [NetworkConfig].
     */
    fun network(block: NetworkConfig.() -> NetworkConfig) { network = network.block() }

    /**
     * Replaces the current [security] configuration with the result of [block].
     *
     * @param block Transformer that returns the new [SecurityConfig].
     */
    fun security(block: SecurityConfig.() -> SecurityConfig) { security = security.block() }

    /**
     * Replaces the current [monitoring] configuration with the result of [block].
     *
     * @param block Transformer that returns the new [MonitoringConfig].
     */
    fun monitoring(block: MonitoringConfig.() -> MonitoringConfig) { monitoring = monitoring.block() }

    /**
     * Factory used to create the underlying [HttpClientEngine] lazily.
     *
     * If `null`, the client builder should select a platform default (e.g., CIO/OkHttp/Darwin).
     * **Lifecycle note:** when supplying a concrete engine (see [engine(engine:)]), ownership
     * and closing semantics remain with the caller unless documented otherwise by the builder.
     */
    var engineFactory: (() -> HttpClientEngine)? = null

    /**
     * Registers a factory that creates a fresh [HttpClientEngine] when the client is built.
     *
     * Prefer this overload so the builder can manage the engine lifecycle.
     *
     * @param factory Provider of the engine to use.
     */
    fun engine(factory: () -> HttpClientEngine) { engineFactory = factory }

    /**
     * Pins a specific, already-created [HttpClientEngine].
     *
     * Use when you need to share an engine or inject a test double.
     * Be mindful of lifecycle/close responsibilities.
     *
     * @param engine The engine instance to use.
     */
    fun engine(engine: HttpClientEngine) { engineFactory = { engine } }
}

/**
 * HTTP methods considered **idempotent** according to RFC 9110 semantics.
 *
 * This is typically used to drive retry/backoff policies and safe replays.
 * Note that idempotency can be violated by server-side behavior; this list
 * expresses the *intended* semantics at the protocol level.
 */
internal val IDEMPOTENT_METHODS: Set<HttpMethod> = setOf(
    HttpMethod.Get,
    HttpMethod.Head,
    HttpMethod.Put,
    HttpMethod.Delete,
)
