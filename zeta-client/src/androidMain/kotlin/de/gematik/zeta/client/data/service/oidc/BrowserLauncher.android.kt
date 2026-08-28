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

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import de.gematik.zeta.client.config.AndroidConfig
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
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

public actual class BrowserLauncher actual constructor(
    public actual val baseUri: String,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val path: String = Url(baseUri).encodedPath
    private val port: Int = Url(baseUri).port
    public actual val redirectUri: String = "$baseUri$APP_PATH"
    public actual val oidcRedirectUri: String = "$baseUri$OIDC_PATH"

    public actual suspend fun launchAndAwaitCallback(authorizeUrl: String, brokerEndpoint: String): String {
        val deferred = CompletableDeferred<String>()

        server = embeddedServer(CIO, port = port) {
            routing {
                get(Url(oidcRedirectUri).encodedPath) {
                    val code = call.request.queryParameters["code"]
                        ?: return@get call.respondText("Missing 'code'", status = HttpStatusCode.BadRequest)
                    val state = call.request.queryParameters["state"]
                        ?: return@get call.respondText("Missing 'state'", status = HttpStatusCode.BadRequest)

                    val brokerUrl = "$brokerEndpoint?code=${code.encodeURLParameter()}&state=${state.encodeURLParameter()}"
                    Log.d { "[BrowserLauncher] relaying inner callback to broker: $brokerUrl" }
                    call.respondRedirect(brokerUrl, permanent = false)
                }
                get(Url(redirectUri).encodedPath) {
                    call.respondText("Login complete, you can close this tab.")
                    deferred.complete(call.request.uri)
                }
            }
        }.start(wait = false)

        Log.d { "[BrowserLauncher] opening custom tab: $authorizeUrl" }
        val customTabsIntent = CustomTabsIntent.Builder().build()
        // launched from the application context, not an activity
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(AndroidConfig.context(), authorizeUrl.toUri())

        val resultPath = try {
            withTimeout(DEFAULT_CALLBACK_TIMEOUT_MILLIS.milliseconds) { deferred.await() }
        } finally {
            server?.stop(500, 1000)
            server = null
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
