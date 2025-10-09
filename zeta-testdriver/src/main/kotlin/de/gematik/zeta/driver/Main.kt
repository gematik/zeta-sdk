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

package de.gematik.zeta.driver

import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.authentication.AuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray

public fun main() {
    val host = System.getenv("LISTEN_HOST") ?: "0.0.0.0"
    val port = System.getenv("LISTEN_PORT")?.toInt() ?: 8080

    embeddedServer(
        Netty,
        port = port,
        host = host,
        module = Application::module,
    ).start(wait = true)
}

public fun Application.module() {
    install(CallLogging)
    install(CORS) {
        anyHost()
        HttpMethod.DefaultMethods.forEach { allowMethod(it) }
    }

    routing()
}

public fun Application.routing() {
    val httpClient = configureClient()

    routing {
        route("/proxy/{...}") {
            handle {
                forward(call, httpClient)
            }
        }

        get("/health") { call.respondText("alive") }
    }
}

private suspend fun forward(
    call: ApplicationCall,
    httpClient: HttpClient,
) {
    val targetUrl = buildTargetUrl(call.request)
    val hasBody =
        call.request.headers.contains(HttpHeaders.ContentType) ||
            call.request.headers.contains(HttpHeaders.TransferEncoding)

    val requestBody = if (hasBody) call.request.receiveChannel().toByteArray() else null

    try {
        val response: HttpResponse =
            httpClient.request(targetUrl) {
                method = call.request.httpMethod
                if (requestBody != null) {
                    setBody(requestBody)
                }
            }

        val status = response.status
        val contentType = response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
        val bytes = response.bodyAsChannel().toByteArray()

        if (contentType != null) {
            call.respondBytes(bytes, contentType = contentType, status = status)
        } else {
            call.respondBytes(bytes, status = status)
        }
    } catch (ex: Throwable) {
        call.respondText(ex.message.toString())
    }
}

private fun buildTargetUrl(request: ApplicationRequest): String {
    val path = request.uri
    val forwardedUrl = path.removePrefix("/proxy/")

    return forwardedUrl
}

private fun configureClient(): HttpClient {
    val authUrl = System.getenv("AUTHSERVER_URL")
        ?: error("Missing required env variable: AUTHSERVER_URL")
    val baseAuthUrl = authUrl.removeSuffix("/protocol/openid-connect/")

    val testFachDienstUrl = System.getenv("FACHDIENST_URL")
        ?: error("Missing required env variable: FACHDIENST_URL")

    val sdk = ZetaSdk.build(
        resource = testFachDienstUrl,
        BuildConfig(
            StorageConfig(),
            object : TpmConfig {},
            AuthConfig(
                listOf(""),
                "",
                "",
                0,
                baseAuthUrl,
            ),
        ),
    )

    return sdk.httpClient()
}
