/*
 * #%L
 * ZETA-SDK
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
package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.authentication.OidcTokenProvider
import de.gematik.zeta.sdk.authentication.oidc.AuthenticationCallback
import de.gematik.zeta.sdk.authentication.oidc.OidcConfig
import de.gematik.zeta.sdk.authentication.oidc.OtpCallback
import de.gematik.zeta.sdk.authentication.oidc.OtpSubmission
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private fun fakeAuthServer(
    issuer: String = "https://zeta-kind.local/auth/realms/zeta-guard",
    authorizationEndpoint: String = "https://zeta-kind.local/auth/realms/zeta-guard/protocol/openid-connect/auth",
) = AuthorizationServerMetadata(
    issuer = issuer,
    authorizationEndpoint = authorizationEndpoint,
    tokenEndpoint = "$issuer/protocol/openid-connect/token",
    nonceEndpoint = "$issuer/zeta-guard-nonce",
    registrationEndpoint = "$issuer/clients-registrations/openid-connect",
    jwksUri = "$issuer/protocol/openid-connect/certs",
    scopesSupported = listOf("openid"),
    responseTypesSupported = listOf("code"),
    responseModesSupported = listOf("query"),
    grantTypesSupported = listOf("authorization_code"),
    tokenEndpointAuthMethodsSupported = listOf("private_key_jwt"),
    tokenEndpointAuthSigningAlgValuesSupported = listOf("ES256"),
    codeChallengeMethodsSupported = listOf("S256"),
)

private val fakeOtpCallback = object : OtpCallback {
    override suspend fun awaitEmail(): String = "test@example.com"
    override suspend fun awaitOtp(emailHint: String?, rejected: Boolean): OtpSubmission =
        OtpSubmission.Otp("123456")
}

private class FakeAuthenticationCallback :
    AuthenticationCallback,
    OidcEndpointAware,
    HttpClientBuilderAware {

    override lateinit var resolveAuthorizationEndpoint: suspend () -> String
    override lateinit var resolveBrokerEndpoint: suspend () -> String
    override lateinit var httpClientBuilder: ZetaHttpClientBuilder

    override suspend fun authenticationCb(clientId: String, requestUri: String): AuthenticationCallback.AuthInfo =
        error("not used in these tests")
}

private fun oidcProvider(callback: AuthenticationCallback?): OidcTokenProvider =
    OidcTokenProvider(
        OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "zeta-sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo",
            authenticationCallback = callback,
            otpCallback = fakeOtpCallback,
        ),
    )

class OidcEndpointResolverTest {
    @Test
    fun configure_shallSetHttpClientBuilder_onAuthenticationCallback_whenHttpClientBuilderAware() {
        val callback = FakeAuthenticationCallback()
        val provider = oidcProvider(callback)
        val builder = ZetaHttpClientBuilder()
        val configurer = OidcEndpointResolver(
            getAuthServer = { fakeAuthServer() },
            httpClientBuilder = builder,
        )

        configurer.resolve(provider)

        assertSame(builder, callback.httpClientBuilder)
    }

    @Test
    fun configure_shallSetResolveAuthorizationEndpoint_toValueFromAuthServer() = runTest {
        val callback = FakeAuthenticationCallback()
        val provider = oidcProvider(callback)
        val authServer = fakeAuthServer(
            authorizationEndpoint = "https://zeta-kind.local/auth/realms/zeta-guard/protocol/openid-connect/auth",
        )
        val configurer = OidcEndpointResolver(
            getAuthServer = { authServer },
            httpClientBuilder = ZetaHttpClientBuilder(),
        )

        configurer.resolve(provider)

        assertEquals(
            "https://zeta-kind.local/auth/realms/zeta-guard/protocol/openid-connect/auth",
            callback.resolveAuthorizationEndpoint(),
        )
    }

    @Test
    fun configure_shallSetResolveBrokerEndpoint_derivedFromIssuerAndIdpAlias() = runTest {
        val callback = FakeAuthenticationCallback()
        val provider = oidcProvider(callback)
        val authServer = fakeAuthServer(issuer = "https://zeta-kind.local/auth/realms/zeta-guard")
        val configurer = OidcEndpointResolver(
            getAuthServer = { authServer },
            httpClientBuilder = ZetaHttpClientBuilder(),
        )

        configurer.resolve(provider)

        assertEquals(
            "https://zeta-kind.local/auth/realms/zeta-guard/broker/zeta-sekidp-oidc/endpoint",
            callback.resolveBrokerEndpoint(),
        )
    }

    @Test
    fun configure_shallSetParBindVerifyResendEndpoints_derivedFromIssuer() = runTest {
        val callback = FakeAuthenticationCallback()
        val provider = oidcProvider(callback)
        val authServer = fakeAuthServer(issuer = "https://zeta-kind.local/auth/realms/zeta-guard")
        val configurer = OidcEndpointResolver(
            getAuthServer = { authServer },
            httpClientBuilder = ZetaHttpClientBuilder(),
        )

        configurer.resolve(provider)

        assertEquals(
            "https://zeta-kind.local/auth/realms/zeta-guard/protocol/openid-connect/ext/par/request",
            provider.resolveParEndpoint(),
        )
        assertEquals(
            "https://zeta-kind.local/auth/realms/zeta-guard/zeta/identity/bind-email",
            provider.resolveBindEmailEndpoint(),
        )
        assertEquals(
            "https://zeta-kind.local/auth/realms/zeta-guard/zeta/identity/bind-email/verify",
            provider.resolveVerifyEmailEndpoint(),
        )
        assertEquals(
            "https://zeta-kind.local/auth/realms/zeta-guard/zeta/identity/bind-email/resend",
            provider.resolveResendEmailEndpoint(),
        )
    }

    @Test
    fun configure_resolvers_shallThrow_whenDiscoveryNotCompleted() = runTest {
        val callback = FakeAuthenticationCallback()
        val provider = oidcProvider(callback)
        val configurer = OidcEndpointResolver(
            getAuthServer = { null },
            httpClientBuilder = ZetaHttpClientBuilder(),
        )

        configurer.resolve(provider)

        assertFailsWith<IllegalStateException> { provider.resolveParEndpoint() }
        assertFailsWith<IllegalStateException> { callback.resolveAuthorizationEndpoint() }
        assertFailsWith<IllegalStateException> { callback.resolveBrokerEndpoint() }
    }

    @Test
    fun configure_shallNotSetAnything_whenAuthenticationCallbackDoesNotImplementAwareInterfaces() {
        val plainCallback = AuthenticationCallback { _, _ -> error("not used") }
        val provider = oidcProvider(plainCallback)
        val configurer = OidcEndpointResolver(
            getAuthServer = { fakeAuthServer() },
            httpClientBuilder = ZetaHttpClientBuilder(),
        )

        configurer.resolve(provider)
    }

    @Test
    fun configure_shallNotThrow_whenAuthenticationCallbackIsNull() = runTest {
        val provider = oidcProvider(callback = null)
        val configurer = OidcEndpointResolver(
            getAuthServer = { fakeAuthServer() },
            httpClientBuilder = ZetaHttpClientBuilder(),
        )

        configurer.resolve(provider)

        assertEquals(
            "https://zeta-kind.local/auth/realms/zeta-guard/protocol/openid-connect/ext/par/request",
            provider.resolveParEndpoint(),
        )
    }
}
