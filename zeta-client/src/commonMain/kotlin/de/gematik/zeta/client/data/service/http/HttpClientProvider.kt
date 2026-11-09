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

package de.gematik.zeta.client.data.service.http

import de.gematik.zeta.client.config.platformProductId
import de.gematik.zeta.client.data.service.smb.HardcodedTokenProvider
import de.gematik.zeta.client.di.DIContainer.ASL_PROD
import de.gematik.zeta.client.di.DIContainer.AUTH_MODE
import de.gematik.zeta.client.di.DIContainer.CUSTOM_SMCB_ENABLED
import de.gematik.zeta.client.di.DIContainer.DISABLE_SERVER_VALIDATION
import de.gematik.zeta.client.di.DIContainer.OIDC_IDP_ALIAS
import de.gematik.zeta.client.di.DIContainer.OIDC_IDP_ISS
import de.gematik.zeta.client.di.DIContainer.REQUIRED_OID
import de.gematik.zeta.client.di.DIContainer.SMB_KEYSTORE_CREDENTIALS
import de.gematik.zeta.client.di.DIContainer.STORAGE_AES_KEY
import de.gematik.zeta.client.notification.NotificationTestAction
import de.gematik.zeta.client.notification.notificationTestAction
import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.SdkStatus
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdk.clearRegistration
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthMode
import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.authentication.SubjectTokenProvider
import de.gematik.zeta.sdk.authentication.identity.ChangeEmailResponse
import de.gematik.zeta.sdk.authentication.identity.EmailChallenge
import de.gematik.zeta.sdk.authentication.identity.models.RegisteredClient
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationLauncher
import de.gematik.zeta.sdk.authentication.oidc.OidcConfig
import de.gematik.zeta.sdk.authentication.oidc.OtpCallback
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.CustomConnectorApi
import de.gematik.zeta.sdk.authentication.smcb.CustomSmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.notifications.NotificationConfig
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.HistoricNotification
import de.gematik.zeta.sdk.storage.StorageConfig
import io.ktor.client.plugins.logging.LogLevel
import kotlin.io.encoding.Base64

public interface HttpClientProvider {
    public fun provideHttpClient(): ZetaHttpClient
    public fun setupEnvUrl(url: String)
    public suspend fun forget()
    public suspend fun clearRegistration()
    public suspend fun logout()
    public suspend fun status(): SdkStatus
    public fun updateTlsValidation(disabled: Boolean)
    public fun updateTrustedCa(pemPath: String?)
    public fun setAuthMode(mode: AuthMode)
    public suspend fun discover()
    public suspend fun register()
    public suspend fun listClients(): List<RegisteredClient>
    public suspend fun deleteClient(clientId: String)
    public suspend fun authenticate()

    /**
     * Registers this device's push pusher at the Notification Service (test action). Fails on
     * platforms/builds without push support and before an environment is selected.
     */
    public suspend fun registerTestPusher(): String
    public fun isPusherConfigured(): Boolean
    public suspend fun subscribeAllChannels(): List<Channel>
    public suspend fun unsubscribeAllChannels(): String
    public suspend fun getHistory(): List<HistoricNotification>
    public suspend fun getHistoricNotification(identifier: String): HistoricNotification
    public suspend fun changeEmail(newEmail: String): ChangeEmailResponse
    public suspend fun resendEmailChangeCode(): EmailChallenge
    public suspend fun verifyEmailChange(code: String)
}

private const val demoClient = "ZETA-Test-Client"

public class HttpClientProviderImpl(
    private val otpCallback: OtpCallback,
) : HttpClientProvider {
    private lateinit var httpClient: ZetaHttpClient
    private lateinit var sdkClient: ZetaSdkClient
    private lateinit var currentUrl: String

    private var tlsValidationDisabled: Boolean = DISABLE_SERVER_VALIDATION
    private var trustedCaPemPath: String? = null
    private var authMode: AuthMode = AUTH_MODE

    override fun provideHttpClient(): ZetaHttpClient =
        httpClient

    override fun setupEnvUrl(url: String) {
        currentUrl = url
        httpClient = prepareHttpClient(url)
    }

    override fun updateTlsValidation(disabled: Boolean) {
        tlsValidationDisabled = disabled
        rebuildIfReady()
    }

    override fun updateTrustedCa(pemPath: String?) {
        trustedCaPemPath = pemPath
        rebuildIfReady()
    }

    override fun setAuthMode(mode: AuthMode) {
        authMode = mode
    }

    private fun rebuildIfReady() {
        if (::currentUrl.isInitialized) {
            httpClient = prepareHttpClient(currentUrl)
        }
    }

    override suspend fun authenticate() {
        sdkClient.authenticate()
    }

    override suspend fun register() {
        sdkClient.register()
    }

    override suspend fun listClients(): List<RegisteredClient> =
        sdkClient.listClients().getOrThrow()

    override suspend fun deleteClient(clientId: String) {
        sdkClient.deleteClient(clientId).getOrThrow()
    }

    override suspend fun discover() {
        sdkClient.discover()
    }

    override suspend fun forget() {
        sdkClient.forget()
        rebuildIfReady()
    }

    override suspend fun clearRegistration() {
        sdkClient.clearRegistration()
    }

    override suspend fun logout() {
        sdkClient.logout()
    }

    override suspend fun status(): SdkStatus = sdkClient.status().getOrThrow()

    override suspend fun registerTestPusher(): String = runNotificationAction { it.run(sdkClient) }

    override fun isPusherConfigured(): Boolean = notificationTestAction()?.hasPusherKey() == true

    override suspend fun subscribeAllChannels(): List<Channel> = runNotificationAction { it.subscribeAllChannels(sdkClient) }

    override suspend fun unsubscribeAllChannels(): String = runNotificationAction { it.unsubscribeAllChannels(sdkClient) }

    override suspend fun getHistory(): List<HistoricNotification> = runNotificationAction { it.getHistory(sdkClient) }

    override suspend fun getHistoricNotification(identifier: String): HistoricNotification =
        runNotificationAction { it.getHistoricNotification(sdkClient, identifier) }

    override suspend fun changeEmail(newEmail: String): ChangeEmailResponse {
        check(::sdkClient.isInitialized) { "Select an environment first" }
        return sdkClient.changeEmail(newEmail).getOrThrow()
    }

    override suspend fun resendEmailChangeCode(): EmailChallenge = sdkClient.resendEmailChangeCode().getOrThrow()

    override suspend fun verifyEmailChange(code: String) {
        sdkClient.verifyEmailChange(code).getOrThrow()
    }

    private suspend fun <T> runNotificationAction(block: suspend (NotificationTestAction) -> T): T {
        val action = notificationTestAction() ?: error("Push notifications are not supported in this build")
        check(::sdkClient.isInitialized) { "Select an environment first" }
        return block(action)
    }

    private fun prepareHttpClient(url: String): ZetaHttpClient {
        val httpClientBuilder = ZetaHttpClientBuilder().apply {
            disableServerValidation(tlsValidationDisabled)
            logging(LogLevel.ALL)
        }

        val tokenProvider = when (authMode) {
            AuthMode.OIDC -> buildOidcTokenProvider()
            AuthMode.SMB -> buildSmbTokenProvider()
        }

        notificationTestAction()?.reset()
        sdkClient = ZetaSdk.build(
            resource = url,
            config = BuildConfig(
                demoClient,
                productVersion = "2.0.4",
                "demo-client",
                StorageConfig.Default(STORAGE_AES_KEY),
                object : TpmConfig {},
                AuthConfig(
                    listOf("zero:audience"),
                    30,
                    ASL_PROD,
                    subjectTokenProvider = tokenProvider,
                    AttestationConfig.software(),
                    requiredRoleOid = REQUIRED_OID ?: "",
                ),
                platformProductId(demoClient),
                httpClientBuilder,
                notificationConfig = NotificationConfig(),
            ),
        )

        return sdkClient.httpClient()
    }

    private fun buildOidcTokenProvider(): OidcTokenProvider {
        val redirects = oidcRedirectUris()

        return OidcTokenProvider(
            OidcConfig(
                redirectUri = redirects.redirectUri,
                oidcRedirectUri = redirects.oidcRedirectUri,
                idpIss = OIDC_IDP_ISS,
                idpAlias = OIDC_IDP_ALIAS,
                authorizationLauncher = defaultAuthorizationLauncher(),
                otpCallback = otpCallback,
            ),
        )
    }
    private fun buildSmbTokenProvider(): SubjectTokenProvider = when {
        SMB_KEYSTORE_CREDENTIALS.keystoreFile.isNotEmpty() ->
            SmbTokenProvider(SMB_KEYSTORE_CREDENTIALS)

        CUSTOM_SMCB_ENABLED ->
            CustomSmcbTokenProvider(
                connectorApi = object : CustomConnectorApi {
                    override suspend fun readCertificate(): ByteArray {
                        // Implement your own certificate retrieval here.
                        return Base64.decode("/ASGePjrYWaieIxzCi1+wEBqjVPQ83x7DOZDuA=...")
                    }

                    override suspend fun externalAuthenticate(base64Challenge: String): ByteArray {
                        // Implement your own signing here.
                        return Base64.decode("/ASGePjrYWaieIxzCi1+wEBqjVPQ83x7DOZDuA=...")
                    }
                },
            )

        else ->
            HardcodedTokenProvider()
    }
}

internal data class OidcRedirectUris(
    val redirectUri: String,
    val oidcRedirectUri: String,
)

internal expect fun oidcRedirectUris(): OidcRedirectUris
internal expect fun defaultAuthorizationLauncher(): AuthorizationLauncher
