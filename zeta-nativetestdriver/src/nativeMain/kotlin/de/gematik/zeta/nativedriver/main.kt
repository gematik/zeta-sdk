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

package de.gematik.zeta.nativedriver

import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val NATIVE_DRIVER_API_PREFIX = "/nativedriver-api"

public fun main() {
    val fachdienstUrl = requireNotNull(
        platform.posix.getenv("FACHDIENST_URL")
            ?.toKString(),
    ) { "FACHDIENST_URL is required" }

    val disableTlsVerification = platform.posix.getenv("DISABLE_SERVER_VALIDATION")
        ?.toKString()
        ?.toBooleanStrictOrNull()
        ?: false

    var sdk: ZetaSdkClient = buildSdk(fachdienstUrl, disableTlsVerification)
    var httpClient: ZetaHttpClient = sdk.httpClient {
        logging(LogLevel.ALL)
        disableServerValidation(disableTlsVerification)
    }

    embeddedServer(CIO, port = 8091) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }

        routing {
            route(NATIVE_DRIVER_API_PREFIX) {
                get("/health") { call.respond(HttpStatusCode.OK, "alive") }

                post("/configure") {
                    val config = call.receive<ConfigureRequest>()
                    sdk = buildSdk(config.resource, config.disableTlsVerification, config.caCertificatePem)

                    httpClient = sdk.httpClient {
                        addCaPem(config.caCertificatePem)
                        logging(LogLevel.ALL)
                        disableServerValidation(config.disableTlsVerification)
                    }

                    call.respondText("Test driver configured successfully", ContentType.Text.Plain)
                }

                get("/hellonative") {
                    val poppHeader = call.request.headers["PoPP"]

                    val result = runCatching {
                        httpClient.get("hellozeta") {
                            poppHeader?.let { headers.append("PoPP", it) }
                        }
                    }
                    result.fold(
                        onSuccess = { response ->
                            call.respond(
                                HttpStatusCode.fromValue(response.status.value),
                                response.body() ?: "",
                            )
                        },
                        onFailure = { e ->
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                """{"error":"${e.message}"}""",
                            )
                        },
                    )
                }
            }
        }
    }.start(wait = true)
}

private fun getPlatformProduct(): PlatformProductId {
    return when (val plat = platform()) {
        is Platform.Jvm.Macos, Platform.Native.Macos -> PlatformProductId.AppleProductId("apple", "macos", listOf())
        is Platform.Jvm.Linux, Platform.Native.Linux -> PlatformProductId.LinuxProductId("linux", "", "", "0.5.0")
        is Platform.Jvm.Windows, Platform.Native.Windows -> PlatformProductId.WindowsProductId("windows", "", "")
        else -> error("Unknown platform: $plat")
    }
}

private fun buildSdk(fachdienstUrl: String, disableTlsVerification: Boolean, caPem: String = ""): ZetaSdkClient {
    val keystoreFile = requireNotNull(
        platform.posix.getenv("SMB_KEYSTORE_FILE")
            ?.toKString(),
    ) { "SMB_KEYSTORE_FILE is required" }
    val keystoreAlias = platform.posix.getenv("SMB_KEYSTORE_ALIAS")?.toKString() ?: ""
    val keystorePassword = platform.posix.getenv("SMB_KEYSTORE_PASSWORD")?.toKString() ?: ""
    val aslProd = platform.posix.getenv("ASL_PROD")?.toKString() == "true"
    val requiredOid = platform.posix.getenv("REQUIRED_ROLE_OID")?.toKString() ?: ""

    return ZetaSdk.build(
        resource = fachdienstUrl,
        BuildConfig(
            productId = "zeta-native-server",
            productVersion = "1.3.0",
            clientName = "zeta-native-server",
            storageConfig = StorageConfig.Custom(InMemoryStorage()),
            tpmConfig = object : TpmConfig {},
            authConfig = AuthConfig(
                scopes = listOf("zero:audience"),
                exp = 30,
                aslProdEnvironment = aslProd,
                subjectTokenProvider = SmbTokenProvider(
                    SmbTokenProvider.Credentials(
                        keystoreFile = keystoreFile,
                        alias = keystoreAlias,
                        password = keystorePassword,
                    ),
                ),
                AttestationConfig.software(),
                requiredOid,
            ),
            platformProductId = getPlatformProduct(),
            ZetaHttpClientBuilder()
                .logging(LogLevel.ALL)
                .addCaPem(caPem)
                .disableServerValidation(disableTlsVerification),
        ),
    )
}

@Serializable
public data class ConfigureRequest(
    val caCertificatePem: String,
    val resource: String,
    val disableTlsVerification: Boolean,
)
