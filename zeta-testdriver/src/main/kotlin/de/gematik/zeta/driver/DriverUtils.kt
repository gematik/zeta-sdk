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

import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.driver.model.toKtorLogLevel
import de.gematik.zeta.driver.oidc.MailCatcherOtpClient
import de.gematik.zeta.driver.oidc.TestDriverAuthenticator
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
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.content.ByteArrayContent
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.utils.io.toByteArray
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
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

internal const val DISABLE_SERVER_VALIDATION = "DISABLE_SERVER_VALIDATION"
internal const val FACHDIENST_URL_REQUIRED_MESSAGE = "fachdienstUrl is required"

internal const val POPP_TOKEN_HEADER_NAME = "popp"

internal fun shouldForwardHeader(name: String, filterHostHeaders: Boolean): Boolean {
    return notForwardedHeaders.none { it.equals(name, ignoreCase = true) } && ((!filterHostHeaders) || optionallyNotForwardedHeaders.none { it.equals(name, ignoreCase = true) })
}

private val notForwardedHeaders = setOf(
    HttpHeaders.ContentType,
    HttpHeaders.ContentLength,
    HttpHeaders.TransferEncoding,
    HttpHeaders.Connection,
)

private val optionallyNotForwardedHeaders = setOf(
    HttpHeaders.Host,
    HttpHeaders.Forwarded,
    HttpHeaders.XForwardedHost,
    HttpHeaders.XForwardedPort,
)

internal suspend fun forward(
    call: ApplicationCall,
    httpClient: ZetaHttpClient,
    config: SdkInstanceConfig,
) {
    val fachdienstUrl = requireNotNull(config.fachdienstUrl) { FACHDIENST_URL_REQUIRED_MESSAGE }
    val targetUrl = buildTargetUrl(call, fachdienstUrl)
    val requestBody = extractRequestBody(call)
    val forwardStart = TimeSource.Monotonic.markNow()

    try {
        val (response, httpTime) = measureTimedValue {
            executeHttpRequest(httpClient, targetUrl, call, requestBody, config.poppToken.ifBlank { null }, config.filterHostHeaders)
        }

        Log.i { "[TESTDRIVER-FORWARD-TIMING] url=$targetUrl method=${call.request.httpMethod.value} http_request=$httpTime status=${response.status}" }
        val (bytes, bodyReadTime) = measureTimedValue { response.body<ByteArray>() }
        Log.i { "[TESTDRIVER-FORWARD-TIMING] url=$targetUrl body_read=$bodyReadTime body_size=${bytes.size}" }

        forwardResponse(call, response, bytes, config.filterHostHeaders)

        Log.i { "[TESTDRIVER-DRIVER-FORWARD-TIMING] url=$targetUrl total=${forwardStart.elapsedNow()}" }
    } catch (ex: Throwable) {
        Log.i { "[TESTDRIVER-DRIVER-FORWARD-TIMING] url=$targetUrl FAILED in ${forwardStart.elapsedNow()}: ${ex.message}" }
        call.respondText(
            ex.message ?: "Unexpected error while forwarding request",
            status = HttpStatusCode.InternalServerError,
        )
    }
}

private suspend fun extractRequestBody(call: ApplicationCall): ByteArray? {
    val hasBody = call.request.headers.contains(HttpHeaders.ContentType) ||
        call.request.headers.contains(HttpHeaders.TransferEncoding)
    return if (hasBody) call.request.receiveChannel().toByteArray() else null
}

internal fun buildForwardHeaders(
    incomingHeaders: Headers,
    poppToken: String?,
    filterHostHeaders: Boolean,
): Headers = Headers.build {
    incomingHeaders.forEach { name, values ->
        if (shouldForwardHeader(name, filterHostHeaders)) appendAll(name, values)
    }
    if (!incomingHeaders.contains(POPP_TOKEN_HEADER_NAME)) {
        poppToken?.let { append(POPP_TOKEN_HEADER_NAME, it) }
    }
}

private suspend fun executeHttpRequest(
    httpClient: ZetaHttpClient,
    targetUrl: String,
    call: ApplicationCall,
    requestBody: ByteArray?,
    poppToken: String?,
    filterHostHeaders: Boolean,
): ZetaHttpResponse {
    val forwardHeaders = buildForwardHeaders(call.request.headers, poppToken, filterHostHeaders)

    return httpClient.request(targetUrl) {
        method = call.request.httpMethod
        headers { headers.appendAll(forwardHeaders) }
        if (requestBody != null) {
            setRequestBody(this, call, requestBody)
        }
    }
}

private fun setRequestBody(
    builder: HttpRequestBuilder,
    call: ApplicationCall,
    requestBody: ByteArray,
) {
    val contentType = call.request.headers[HttpHeaders.ContentType]
    if (contentType != null) {
        builder.setBody(ByteArrayContent(requestBody, ContentType.parse(contentType)))
    } else {
        builder.setBody(requestBody)
    }
}

private suspend fun forwardResponse(
    call: ApplicationCall,
    response: ZetaHttpResponse,
    bytes: ByteArray,
    filterHostHeaders: Boolean,
) {
    response.headers.forEach { (name, value) ->
        if (shouldForwardHeader(name, filterHostHeaders)) call.response.headers.append(name, value)
    }

    val contentType = response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
    if (contentType != null) {
        call.respondBytes(bytes, contentType = contentType, status = response.status)
    } else {
        call.respondBytes(bytes, status = response.status)
    }
}

internal fun buildTargetUrl(call: ApplicationCall, fachdienstUrl: String): String {
    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
    return "${fachdienstUrl.trimEnd('/')}/$path"
}

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
            "1.3.0",
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
        .build()

    CoroutineScope(Dispatchers.Default).launch {
        autoFulfillOtpFromMailCatcher(otpCallback, mailCatcherOtpClient, mailCatcherHttpClient)
    }

    return OidcTokenProvider(
        OidcConfig(
            requestUri = baseUri,
            idpIss = idpIss,
            idpAlias = idpAlias,
            authenticationCallback = TestDriverAuthenticator(appBaseUri = baseUri, testKvnr = testKvnr),
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
