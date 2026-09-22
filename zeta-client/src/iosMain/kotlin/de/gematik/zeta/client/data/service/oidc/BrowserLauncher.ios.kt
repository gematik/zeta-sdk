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
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds

public actual class BrowserLauncher actual constructor(
    public actual val baseUri: String,
) {
    private var server: EmbeddedServer<*, *>? = null
    private var safariViewController: SFSafariViewController? = null
    private val port: Int = Url(baseUri).port
    private val path: String = Url(baseUri).encodedPath

    public actual val redirectUri: String = "$baseUri$APP_PATH"
    public actual val oidcRedirectUri: String = "$baseUri$OIDC_PATH"

    public actual suspend fun launchAndAwaitCallback(authorizeUrl: String, brokerEndpoint: String): String {
        val deferred = CompletableDeferred<String>()

        server = embeddedServer(CIO, port = port) {
            routing {
                get("$path$OIDC_PATH") {
                    val code = call.request.queryParameters["code"]
                        ?: return@get call.respondText("Missing 'code' param", status = HttpStatusCode.BadRequest)
                    val state = call.request.queryParameters["state"]
                        ?: return@get call.respondText("Missing 'state' param", status = HttpStatusCode.BadRequest)

                    val brokerUrl = "$brokerEndpoint?code=${code.encodeURLParameter()}&state=${state.encodeURLParameter()}"
                    Log.d { "[BrowserLauncher] relaying inner callback to broker: $brokerUrl" }
                    call.respondRedirect(brokerUrl)
                }
                get("$path$APP_PATH") {
                    call.respondText("Login complete, you can close this tab.")
                    deferred.complete(call.request.uri)
                }
            }
        }.start(wait = false)

        Log.d { "[BrowserLauncher] opening system browser: $authorizeUrl" }
        withContext(Dispatchers.Main) {
            val controller = SFSafariViewController(NSURL(string = authorizeUrl))
            safariViewController = controller
            UIApplication.sharedApplication.keyWindow?.rootViewController
                ?.presentViewController(controller, animated = true, completion = null)
        }

        val resultPath = try {
            withTimeout(DEFAULT_CALLBACK_TIMEOUT_MILLIS.milliseconds) { deferred.await() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                // Wait for the dismissal transition to finish. UIKit drops a presentation that starts
                // while another transition is still running, which silently loses the OTP dialog that
                // the token flow puts on screen right after this call returns.
                safariViewController?.let { controller ->
                    suspendCoroutine { continuation ->
                        controller.dismissViewControllerAnimated(true) { continuation.resume(Unit) }
                    }
                }
                safariViewController = null
                server?.stop(500, 1000)
                server = null
            }
        }

        val schemeAndAuthority = baseUri.substringBefore(path)
        return "$schemeAndAuthority$resultPath"
    }

    private companion object {
        const val OIDC_PATH = "/oidc"
        const val APP_PATH = "/app"
        const val DEFAULT_CALLBACK_TIMEOUT_MILLIS = 5 * 60 * 1000L // 5 minutes
    }
}
