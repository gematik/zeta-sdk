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

package de.gematik.zeta.android.testdriver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import de.gematik.zeta.android.testdriver.oidc.MailCatcherOtpCallback
import de.gematik.zeta.client.notification.PushNotifications
import de.gematik.zeta.driver.core.ProxyConfig
import de.gematik.zeta.driver.core.ProxyTarget
import de.gematik.zeta.driver.core.oidc.MailCatcherOtpClient
import de.gematik.zeta.driver.core.oidc.TestDriverAuthorizationLauncher
import de.gematik.zeta.driver.core.proxyRoutes
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaInit
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.authentication.oidc.OidcConfig
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.notifications.model.ChannelStatus
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

public class MainActivity : ComponentActivity() {
    private companion object {
        @Volatile
        var serverStarted = false
        private const val PREFS_NAME = "zeta_demo_app"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val ANDROID_DRIVER_API_PREFIX = "/androiddriver-api"
        private const val ANDROID_DRIVER_PROXY_PREFIX = "/androidproxy"
        private const val ANDROID_DRIVER_NOTIFICATIONS_PREFIX = "/notifications"
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i { "POST_NOTIFICATIONS granted=$granted" }
        }

    private lateinit var sdkClient: ZetaSdkClient
    private lateinit var zetaHttpClient: ZetaHttpClient
    private lateinit var tokenProvider: OidcTokenProvider
    private lateinit var fachdienst: String
    private var pushGatewayUrl = ""
    private var pusherAppId = ""
    private val pushEnabled get() = pushGatewayUrl.isNotBlank() && FirebaseApp.getApps(this).isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (serverStarted) {
            Log.i { "activity recreated, server already running" }
            return
        }
        serverStarted = true

        initializeApp()
        pushGatewayUrl = extra("PUSH_GATEWAY_URL")
        pusherAppId = extra("PUSHER_APP_ID")

        initializeFirebase()
        initializeSdk(extra("FACHDIENST_URL"))
        startServer(extra("POPP_TOKEN"))
    }

    private fun initializeApp() {
        Log.initDebugLogger()
        ZetaInit.initAndroid(this)
        requestNotificationPermissionIfNeeded()
    }

    private fun buildSdkClient() {
        sdkClient = ZetaSdk.build(
            resource = fachdienst,
            config = BuildConfig(
                clientName = "sdk-client",
                productVersion = "2.0.4",
                productId = "ZETA-Test-Client",
                storageConfig = StorageConfig.Custom(InMemoryStorage()),
                authConfig = AuthConfig(
                    listOf("zero:audience"),
                    30,
                    false,
                    subjectTokenProvider = tokenProvider,
                    attestation = AttestationConfig.software(),
                    requiredRoleOid = "",
                ),
                platformProductId = PlatformProductId.LinuxProductId(
                    "linux",
                    "",
                    "ZETA-Hellozeta-Client",
                    "0.5.0",
                ),
                httpClientBuilder = createHttpClientBuilder(),
                notificationConfig = NotificationConfig(),
                tpmConfig = object : TpmConfig {},
            ),
        )

        zetaHttpClient = sdkClient.httpClient {
            logging(LogLevel.ALL)
            disableServerValidation(true)
        }

        if (pushEnabled) PushNotifications.attach(sdkClient, pushGatewayUrl, pusherAppId, extra("FIREBASE_PROJECT_ID"))
    }

    private fun initializeSdk(fachdienst: String) {
        this.fachdienst = fachdienst
        tokenProvider = buildOidcTokenProvider(fachdienst)
        buildSdkClient()

        Log.i { "SDK initialized" }
    }

    private fun startServer(poppToken: String) {
        embeddedServer(
            factory = CIO,
            host = "127.0.0.1",
            port = 8081,
        ) {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            routing {
                registerDriverRoutes()
                get(ANDROID_DRIVER_PROXY_PREFIX) {
                    call.respondText("path required, e.g. $ANDROID_DRIVER_PROXY_PREFIX/hellozeta", status = HttpStatusCode.BadRequest)
                }
                registerProxyRoutes(poppToken)
            }
        }.start(wait = false)

        Log.i { "Android TestDriver server started" }
    }

    private fun Routing.registerDriverRoutes() {
        route(ANDROID_DRIVER_API_PREFIX) {
            get("/health") {
                call.respond(HttpStatusCode.OK, "alive")
            }

            get("reset") {
                PushNotifications.reset()
                runCatching { sdkClient.forget() }
                buildSdkClient()
                call.respondText("reset")
            }

            if (pushEnabled) registerNotificationRoutes()
        }
    }

    private fun Route.registerNotificationRoutes() {
        route(ANDROID_DRIVER_NOTIFICATIONS_PREFIX) {
            post("register") { call.respondText(PushNotifications.register()) }
            post("subscribe-all") { call.respond(PushNotifications.setAllChannels(ChannelStatus.ENABLED)) }
            post("unsubscribe-all") { call.respondText("Unsubscribed all channels (${PushNotifications.setAllChannels(ChannelStatus.DISABLED).size})") }
            get("received") { call.respond(PushNotifications.received()) }
            delete("received") { PushNotifications.clearReceived(); call.respondText("cleared") }
        }
    }

    private fun Routing.registerProxyRoutes(poppToken: String) {
        proxyRoutes(ANDROID_DRIVER_PROXY_PREFIX) {
            ProxyTarget(
                sdk = sdkClient,
                httpClient = zetaHttpClient,
                config = ProxyConfig(
                    targetBaseUrl = fachdienst,
                    poppToken = poppToken,
                    filterHostHeaders = true,
                ),
            )
        }
    }

    private fun createHttpClientBuilder() =
        ZetaHttpClientBuilder().apply {
            disableServerValidation(true)
            logging(LogLevel.ALL)
        }
    private fun extra(name: String): String = intent.getStringExtra(name) ?: ""

    private fun buildOidcTokenProvider(fachdienstUrl: String): OidcTokenProvider {
        val baseUri = requiredExtra("OIDC_BASE_URI")
        val idpIss = requiredExtra("OIDC_IDP_ISS")
        val idpAlias = requiredExtra("OIDC_IDP_ALIAS")
        val testKvnr = requiredExtra("OIDC_TEST_KVNR")
        val bindingEmail = extra("OIDC_BINDING_EMAIL").ifBlank { null }
        val httpClientBuilder = ZetaHttpClientBuilder().disableServerValidation(true)
        val mailCatcher = MailCatcherOtpClient(mailCatcherUrlFrom(fachdienstUrl))
        val otpCallback = MailCatcherOtpCallback(bindingEmail, mailCatcher, httpClientBuilder.build())

        return OidcTokenProvider(
            OidcConfig(
                redirectUri = "$baseUri/app",
                oidcRedirectUri = "$baseUri/oidc",
                idpIss = idpIss,
                idpAlias = idpAlias,
                authorizationLauncher = TestDriverAuthorizationLauncher(httpClientBuilder, testKvnr),
                otpCallback = otpCallback,
            ),
        )
    }

    private fun requiredExtra(name: String): String =
        extra(name).also {
            require(it.isNotBlank()) { "$name is required" }
        }

    private fun mailCatcherUrlFrom(fachdienstUrl: String): String {
        val url = Url(fachdienstUrl)
        return "${url.protocol.name}://${url.host}/mailcatcher"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return
        prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }

        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun initializeFirebase() {
        val appId = extra("FIREBASE_APP_ID") // mobilesdk_app_id
        if (appId.isBlank()) return
        if (FirebaseApp.getApps(this).isNotEmpty()) return
        FirebaseApp.initializeApp(
            this,
            FirebaseOptions.Builder()
                .setApplicationId(appId) // client[].client_info.mobilesdk_app_id
                .setApiKey(extra("FIREBASE_API_KEY")) // client[].api_key[0].current_key
                .setProjectId(extra("FIREBASE_PROJECT_ID")) // project_info.project_id
                .setGcmSenderId(extra("FIREBASE_SENDER_ID")) // project_info.project_number
                .build(),
        )
    }
}
