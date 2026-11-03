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

package de.gematik.zeta.client.data.service.oidc

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationLauncher
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.net.BindException
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public class LoopbackAuthorizationLauncher(
    private val callbackTimeout: Duration = 5.minutes,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthorizationLauncher {

    private val mutex = Mutex()

    override suspend fun open(request: AuthorizationRequest): String =
        mutex.withLock {
            val redirectUrl = Url(request.redirectUri)
            val oidcRedirectUrl = Url(request.oidcRedirectUri)

            require(redirectUrl.host in LOOPBACK_HOSTS) {
                "redirectUri must point to a loopback host, was ${redirectUrl.host}"
            }
            require(redirectUrl.host == oidcRedirectUrl.host && redirectUrl.port == oidcRedirectUrl.port) {
                "redirectUri and oidcRedirectUri must share host and port (one callback server serves both)"
            }

            val result = CompletableDeferred<String>()

            val server = try {
                embeddedServer(CIO, host = redirectUrl.host, port = redirectUrl.port) {
                    routing {
                        get(oidcRedirectUrl.encodedPath) {
                            val params = call.request.queryParameters

                            params["error"]?.let { error ->
                                call.respondText("Login failed: $error", status = HttpStatusCode.BadRequest)
                                result.completeExceptionally(
                                    AuthorizationFailedException(error, params["error_description"]),
                                )
                                return@get
                            }

                            val code = params["code"]
                                ?: return@get call.respondText("Missing 'code'", status = HttpStatusCode.BadRequest)
                            val state = params["state"]
                                ?: return@get call.respondText("Missing 'state'", status = HttpStatusCode.BadRequest)

                            val brokerUrl = URLBuilder(request.brokerEndpoint).apply {
                                parameters.append("code", code)
                                parameters.append("state", state)
                            }.buildString()

                            Log.d { "[LoopbackAuthorizationLauncher] relaying OIDC callback to broker" }
                            call.respondRedirect(brokerUrl, permanent = false)
                        }

                        get(redirectUrl.encodedPath) {
                            call.respondText("Login complete, you can close this tab.")
                            result.complete(call.request.uri)
                        }
                    }
                }.start(wait = false)
            } catch (e: BindException) {
                throw IllegalStateException(
                    "Port ${redirectUrl.port} is already in use; cannot start callback server", e,
                )
            }

            try {
                openBrowser(request.authorizationUrl)
                val resultPath = withTimeout(callbackTimeout) { result.await() }
                "${redirectUrl.protocol.name}://${redirectUrl.host}:${redirectUrl.port}$resultPath"
            } finally {
                server.stop(500, 1000)
            }
        }

    private suspend fun openBrowser(url: String) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            "No system browser available (headless environment?)"
        }
        Log.d { "[LoopbackAuthorizationLauncher] opening system browser: ${url.redacted()}" }
        withContext(ioDispatcher) { Desktop.getDesktop().browse(URI(url)) }
    }

    private companion object {
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")
    }
}

public class AuthorizationFailedException(
    public val error: String,
    public val description: String?,
) : Exception("Authorization failed: $error${description?.let { " ($it)" } ?: ""}")

internal fun String.redacted(): String = substringBefore('?') + if (contains('?')) "?<redacted>" else ""
