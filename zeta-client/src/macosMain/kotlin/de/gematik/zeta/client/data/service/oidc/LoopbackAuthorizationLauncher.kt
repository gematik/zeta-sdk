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
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationFailedException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public class LoopbackAuthorizationLauncher(
    private val callbackTimeout: Duration = 5.minutes,
) : AuthorizationLauncher {

    private val mutex = Mutex()

    override suspend fun open(request: AuthorizationRequest): String =
        mutex.withLock {
            val redirectUrl = Url(request.redirectUri)
            val oidcRedirectUrl = Url(request.oidcRedirectUri)

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
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Could not start callback server on port ${redirectUrl.port} (already in use?)", e,
                )
            }

            try {
                Log.d { "[LoopbackAuthorizationLauncher] opening system browser: ${request.authorizationUrl}" }

                check(NSWorkspace.sharedWorkspace.openURL(NSURL(string = request.authorizationUrl))) {
                    "Could not open system browser"
                }

                val resultPath = withTimeout(callbackTimeout) { result.await() }
                "${redirectUrl.protocol.name}://${redirectUrl.host}:${redirectUrl.port}$resultPath"
            } finally {
                server.stop(500, 1000)
            }
        }
}
