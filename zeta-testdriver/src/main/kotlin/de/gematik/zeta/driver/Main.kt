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

import de.gematik.zeta.logging.Log
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import java.lang.System

public fun main() {
    Log.initDebugLogger()

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
    install(WebSockets)
    install(CORS) {
        anyHost()
        HttpMethod.DefaultMethods.forEach { allowMethod(it) }
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = true
            },
        )
    }

    testDriverRouting()
    loadTestDriverRouting()
}
