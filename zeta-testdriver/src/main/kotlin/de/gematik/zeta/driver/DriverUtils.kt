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

import de.gematik.zeta.driver.core.ProxyConfig
import de.gematik.zeta.driver.core.ProxyTarget
import de.gematik.zeta.driver.core.oidc.MailCatcherOtpClient
import de.gematik.zeta.driver.core.oidc.TestDriverAuthorizationLauncher
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.driver.model.toKtorLogLevel
import de.gematik.zeta.driver.oidc.TestDriverOtpCallback
import de.gematik.zeta.logging.Log
import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthMode
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.authentication.oidc.OidcConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val DISABLE_SERVER_VALIDATION = "DISABLE_SERVER_VALIDATION"
internal const val FACHDIENST_URL_REQUIRED_MESSAGE = "fachdienstUrl is required"
internal const val POPP_TOKEN_HEADER_NAME = "popp"

public suspend fun forwardWs(
    serverSession: DefaultWebSocketSession,
    sdk: ZetaSdkClient,
    targetUrl: String,
    config: SdkInstanceConfig,
): Unit = coroutineScope {
    val customHeaders = wsCustomHeaders(config.poppToken)

    sdk.ws(targetUrl, wsClientConfig(config), customHeaders) {
        val backendSession = this

        val clientToBackend = launch { forwardFrames(serverSession, backendSession) }
        val backendToClient = launch { forwardFrames(backendSession, serverSession) }

        clientToBackend.join()
        backendToClient.join()
    }
}

private fun wsCustomHeaders(poppToken: String?): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    poppToken?.let { headers[POPP_TOKEN_HEADER_NAME] = it }

    return headers
}

public fun buildWsTargetUrl(
    call: ApplicationCall,
    config: SdkInstanceConfig,
    prefixToRemove: String = "/proxy",
): String {
    val fachdienstUrl = requireNotNull(config.fachdienstUrl) { FACHDIENST_URL_REQUIRED_MESSAGE }
    val base = Url(fachdienstUrl)
    val afterProxy = call
        .request
        .path()
        .removePrefix(prefixToRemove)
        .trimStart('/')

    val wsProtocol = when (base.protocol) {
        URLProtocol.HTTPS -> URLProtocol.WSS
        URLProtocol.HTTP -> URLProtocol.WS
        else -> base.protocol
    }
    val builder = URLBuilder().apply {
        protocol = wsProtocol
        host = base.host
        port = base.port
        encodedPath = buildString {
            append(base.encodedPath.trimEnd('/'))
            if (afterProxy.isNotEmpty()) {
                append('/')
                append(afterProxy)
            }
        }
    }
    return builder.buildString()
}

private fun wsClientConfig(instanceConfig: SdkInstanceConfig): ZetaHttpClientBuilder.() -> Unit = {
    logging(Log.logLevel.toKtorLogLevel())
    disableServerValidation(instanceConfig.disableTlsVerification)
}

private suspend fun forwardFrames(
    from: DefaultWebSocketSession,
    to: DefaultWebSocketSession,
) {
    for (frame in from.incoming) {
        when (frame) {
            is Frame.Text -> to.send(Frame.Text(frame.readText()))

            is Frame.Binary -> to.send(Frame.Binary(true, frame.readBytes()))

            is Frame.Close -> {
                to.send(Frame.Close(frame.readReason() ?: CloseReason(CloseReason.Codes.NORMAL, "Closed")))
                return
            }

            else -> Unit
        }
    }
}

public fun newSdk(
    storage: SdkStorage,
    config: SdkInstanceConfig,
    otpCallback: TestDriverOtpCallback = TestDriverOtpCallback(config.oidcBindingEmail),
): ZetaSdkClient {
    val fachdienstUrl = requireNotNull(config.fachdienstUrl) { FACHDIENST_URL_REQUIRED_MESSAGE }

    val tokenProvider = when (config.authMode) {
        AuthMode.OIDC -> buildOidcTokenProvider(config, otpCallback)
        AuthMode.SMB -> buildSmbTokenProvider(config)
    }

    return ZetaSdk.build(
        resource = fachdienstUrl,
        BuildConfig(
            "test-proxy",
            "2.0.6",
            "sdk-client",
            StorageConfig.Custom(storage),
            object : TpmConfig {},
            AuthConfig(
                listOf("zero:audience"),
                30,
                aslProdEnvironment = config.aslProdEnv,
                subjectTokenProvider = tokenProvider,
                requiredRoleOid = config.requiredOid,
            ),
            platformProductId = getPlatformProduct(),
            ZetaHttpClientBuilder()
                .timeouts(20000, 20000)
                .disableServerValidation(config.disableTlsVerification)
                .logging(Log.logLevel.toKtorLogLevel())
                .contentNegotiation(true)
                .apply {
                    customCaPems.forEach { pem ->
                        addCaPem(pem)
                    }
                },
            // Enable the Notification Service client so the driver can expose push endpoints.
            // Lazy: the client and NS discovery only materialise on first notification call,
            // so existing proxy/control flows are unaffected. Defaults target `/push/v1`.
            notificationConfig = NotificationConfig(),
        ),
    )
}

private fun buildOidcTokenProvider(
    config: SdkInstanceConfig,
    otpCallback: TestDriverOtpCallback,
): OidcTokenProvider {
    val baseUri = requireNotNull(config.oidcBaseUri) { "oidcBaseUri is required for OIDC mode" }
    val idpIss = requireNotNull(config.oidcIdpIss) { "oidcIdpIss is required for OIDC mode" }
    val idpAlias = requireNotNull(config.oidcIdpAlias) { "oidcIdpAlias is required for OIDC mode" }
    val testKvnr = requireNotNull(config.oidcTestKvnr) { "oidcTestKvnr is required for OIDC mode" }
    val fachdienstUrl = requireNotNull(config.fachdienstUrl) { FACHDIENST_URL_REQUIRED_MESSAGE }

    val mailCatcherOtpClient = MailCatcherOtpClient(mailCatcherUrlFrom(fachdienstUrl))
    val mailCatcherHttpClient = ZetaHttpClientBuilder()
        .disableServerValidation(config.disableTlsVerification)
        .logging(Log.logLevel.toKtorLogLevel())

    CoroutineScope(Dispatchers.Default).launch {
        autoFulfillOtpFromMailCatcher(otpCallback, mailCatcherOtpClient, mailCatcherHttpClient.build())
    }

    val redirectUri = "$baseUri/app"
    val oidcRedirectUri = "$baseUri/oidc"

    return OidcTokenProvider(
        OidcConfig(
            redirectUri = redirectUri,
            oidcRedirectUri = oidcRedirectUri,
            idpIss = idpIss,
            idpAlias = idpAlias,
            authorizationLauncher = TestDriverAuthorizationLauncher(mailCatcherHttpClient, testKvnr),
            otpCallback = otpCallback,
        ),
    )
}

private suspend fun autoFulfillOtpFromMailCatcher(
    otpCallback: TestDriverOtpCallback,
    mailCatcherOtpClient: MailCatcherOtpClient,
    httpClient: ZetaHttpClient,
) {
    while (otpCallback.currentlyAwaiting() != "otp") {
        delay(200.milliseconds)
    }
    val code = mailCatcherOtpClient.waitForOtp(httpClient)
    otpCallback.provideOtp(code)
}

private fun mailCatcherUrlFrom(fachdienstUrl: String): String {
    val url = Url(fachdienstUrl)
    return "${url.protocol.name}://${url.host}/mailcatcher"
}

private fun buildSmbTokenProvider(config: SdkInstanceConfig): SubjectTokenProvider = when {
    config.smbKeystoreB64.isNotEmpty() ->
        SmbTokenProvider(
            SmbTokenProvider.Credentials(
                keystoreB64 = config.smbKeystoreB64,
                alias = config.smbKeystoreAlias,
                password = config.smbKeystorePassword,
            ),
        )

    config.smbKeystoreFile.isNotEmpty() ->
        SmbTokenProvider(
            SmbTokenProvider.Credentials(
                config.smbKeystoreFile,
                config.smbKeystoreAlias,
                config.smbKeystorePassword,
            ),
        )

    else ->
        error("No SM-B or SMC-B configuration was provided")
}

private fun getPlatformProduct(): PlatformProductId {
    return when (val plat = platform()) {
        is Platform.Jvm.Macos, Platform.Native.Macos -> PlatformProductId.AppleProductId("apple", "macos", listOf())
        is Platform.Jvm.Linux -> PlatformProductId.LinuxProductId("linux", "", "demo-client", "0.5.0")
        is Platform.Jvm.Windows -> PlatformProductId.WindowsProductId("windows", "", "demo-client")
        else -> error("Unknown platform: $plat")
    }
}

public suspend fun reset(
    call: ApplicationCall,
    sdkClient: ZetaSdkClient,
) {
    try {
        sdkClient.forget()

        call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun authenticate(
    call: ApplicationCall,
    sdk: ZetaSdkClient,
) {
    try {
        Log.i { "[SDK-DRIVER] Authenticate called" }
        val result = sdk.authenticate()
        if (result.isSuccess) {
            call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
        } else {
            call.respond(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        }
    } catch (ex: Throwable) {
        Log.e { "[SDK-DRIVER] Authenticate failed:" + ex.message }
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun discover(
    call: ApplicationCall,
    sdk: ZetaSdkClient,
) {
    try {
        val result = sdk.discover()
        if (result.isSuccess) {
            call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
        } else {
            call.respond(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        }
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

public suspend fun register(
    call: ApplicationCall,
    sdk: ZetaSdkClient,
) {
    try {
        val result = sdk.register()
        if (result.isSuccess) {
            call.respond(HttpStatusCode.OK, HttpStatusCode.OK.description)
        } else {
            call.respond(HttpStatusCode.Forbidden, HttpStatusCode.Forbidden.description)
        }
    } catch (ex: Throwable) {
        call.respond(HttpStatusCode.InternalServerError, ex.message.toString())
    }
}

internal fun SdkInstanceConfig.toProxyTarget(sdk: ZetaSdkClient, httpClient: ZetaHttpClient): ProxyTarget =
    ProxyTarget(
        sdk = sdk,
        httpClient = httpClient,
        config = ProxyConfig(
            targetBaseUrl = requireNotNull(fachdienstUrl) { FACHDIENST_URL_REQUIRED_MESSAGE },
            poppToken = poppToken,
            filterHostHeaders = filterHostHeaders,
            disableTlsVerification = disableTlsVerification,
            logLevel = Log.logLevel.toKtorLogLevel(),
        ),
    )
