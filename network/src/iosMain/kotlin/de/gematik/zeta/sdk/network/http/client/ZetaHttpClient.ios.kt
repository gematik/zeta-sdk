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
import de.gematik.zeta.sdk.network.http.client.tls.DarwinSecurityUtils
import de.gematik.zeta.sdk.network.http.client.tls.DarwinTlsValidator
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust

/**
 * iOS HTTP client with in-handshake revocation checking.
 *
 * NSURLSession does not expose the stapled OCSP response, so it is fetched via a short
 * Network.framework probe connection and used only if the probe's leaf matches the
 * validated one. Validation uses the common `RevocationChecker`.
 *
 * `disableServerValidation` skips all checks (test environments only).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun buildPlatformClient(
    cfg: ClientConfig,
    dependencies: HttpClientDependencies,
    commonSetup: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tlsValidator = DarwinTlsValidator { staple, chain ->
        dependencies.revocationChecker?.validateChain(
            stapledOcspResponse = staple,
            chain = chain,
        )
    }

    val disableServerValidation = cfg.security.disableServerValidation

    if (disableServerValidation) {
        Log.w { "[TLS] Server certificate validation is DISABLED. Do not use in production." }
    }

    return HttpClient(Darwin) {
        commonSetup()

        engine {
            handleChallenge { _, _, challenge, completion ->
                val protectionSpace = challenge.protectionSpace

                if (protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
                    completion(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
                    return@handleChallenge
                }

                val trust = protectionSpace.serverTrust
                val host = protectionSpace.host
                val port = protectionSpace.port.toInt().takeIf { it > 0 } ?: 443

                if (trust == null) {
                    Log.e { "[TLS] No serverTrust for $host" }
                    completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)

                    return@handleChallenge
                }

                if (disableServerValidation) {
                    completion(
                        NSURLSessionAuthChallengeUseCredential.convert(),
                        NSURLCredential.credentialForTrust(trust),
                    )
                    return@handleChallenge
                }

                if (!DarwinSecurityUtils.evaluateTrust(trust, host)) {
                    completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    return@handleChallenge
                }

                val chain = try {
                    DarwinSecurityUtils.extractCertificateChain(trust)
                } catch (t: Throwable) {
                    Log.e { "[TLS] Could not extract certificate chain host=$host: ${t::class.simpleName}: ${t.message}" }

                    completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    return@handleChallenge
                }

                if (chain.size < 2) {
                    Log.e { "[TLS] Certificate chain does not contain leaf + issuer host=$host" }

                    completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    return@handleChallenge
                }

                // Create while the NSURLSession challenge / SecTrust is alive.
                val credential = NSURLCredential.credentialForTrust(trust)

                scope.launch {
                    try {
                        tlsValidator.validate(
                            host = host,
                            port = port,
                            chain = chain,
                        )

                        completion(NSURLSessionAuthChallengeUseCredential.convert(), credential)
                    } catch (t: Throwable) {
                        Log.e { "[TLS] Revocation validation FAILED host=$host type=${t::class.simpleName} message=${t.message}" }

                        completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    }
                }
            }
        }
    }
}
