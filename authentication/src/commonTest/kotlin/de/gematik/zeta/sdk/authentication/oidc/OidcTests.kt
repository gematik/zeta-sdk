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
package de.gematik.zeta.sdk.authentication.oidc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private val fakeOtpCallback = object : OtpCallback {
    override suspend fun awaitEmail(): String = "test@example.com"

    override suspend fun awaitOtp(
        emailHint: String?,
        rejected: Boolean,
    ): OtpSubmission = OtpSubmission.Otp("123456")
}

private val fakeLauncher = AuthorizationLauncher { request -> "${request.redirectUri}?code=test-code&state=test-state" }

private fun oidcConfig(
    redirectUri: String = "http://localhost:8090/cb/demo/app",
    oidcRedirectUri: String = "http://localhost:8090/cb/demo/oidc",
    authorizationLauncher: AuthorizationLauncher = fakeLauncher,
) = OidcConfig(
    redirectUri = redirectUri,
    oidcRedirectUri = oidcRedirectUri,
    idpIss = "https://sekidp.example.org",
    idpAlias = "sekidp-oidc",
    authorizationLauncher = authorizationLauncher,
    otpCallback = fakeOtpCallback,
)

class OidcTests {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oidcConfig_shallKeepRedirectUrisUnchanged() {
        val config = oidcConfig()

        assertEquals("http://localhost:8090/cb/demo/app", config.redirectUri)
        assertEquals("http://localhost:8090/cb/demo/oidc", config.oidcRedirectUri)
    }

    @Test
    fun oidcConfig_redirectUriAndOidcRedirectUri_shallBeDistinct() {
        val config = oidcConfig()

        assertNotEquals(config.redirectUri, config.oidcRedirectUri)
    }

    @Test
    fun oidcConfig_shallKeepRedirectUris_forDifferentInstances() {
        val configA = oidcConfig()
        val configB = oidcConfig(
            redirectUri = "https://my.client.local/cb/otherapp/app",
            oidcRedirectUri = "https://my.client.local/cb/otherapp/oidc",
        )

        assertEquals("http://localhost:8090/cb/demo/app", configA.redirectUri)
        assertEquals("https://my.client.local/cb/otherapp/app", configB.redirectUri)
    }

    @Test
    fun oidcConfig_shallKeepProvidedAuthorizationLauncher() {
        val launcher = AuthorizationLauncher { "de.gematik.zeta://app?code=c&state=s" }

        val config = oidcConfig(authorizationLauncher = launcher)

        assertSame(launcher, config.authorizationLauncher)
    }

    @Test
    fun equals_shallHoldForDataClassWithIdenticalConstructorArgs() {
        val configA = oidcConfig()
        val configB = configA.copy()

        assertEquals(configA, configB)
        assertEquals(configA.redirectUri, configB.redirectUri)
        assertEquals(configA.oidcRedirectUri, configB.oidcRedirectUri)
    }

    @Test
    fun authorizationRequest_shallExposeAllUrls() {
        val request = AuthorizationRequest(
            authorizationUrl = "https://kc.example.org/realms/zeta/protocol/openid-connect/auth?client_id=c&request_uri=r",
            redirectUri = "http://localhost:8090/cb/demo/app",
            oidcRedirectUri = "http://localhost:8090/cb/demo/oidc",
            brokerEndpoint = "https://kc.example.org/realms/zeta/broker/sekidp-oidc/endpoint",
        )

        assertEquals("http://localhost:8090/cb/demo/app", request.redirectUri)
        assertEquals("http://localhost:8090/cb/demo/oidc", request.oidcRedirectUri)
        assertEquals("https://kc.example.org/realms/zeta/broker/sekidp-oidc/endpoint", request.brokerEndpoint)
    }

    @Test
    fun authorizationLauncher_shallReturnFinalRedirectUrl() = kotlinx.coroutines.test.runTest {
        val request = AuthorizationRequest(
            authorizationUrl = "https://kc.example.org/auth",
            redirectUri = "http://localhost:8090/cb/demo/app",
            oidcRedirectUri = "http://localhost:8090/cb/demo/oidc",
            brokerEndpoint = "https://kc.example.org/broker/endpoint",
        )

        val finalUrl = fakeLauncher.open(request)

        assertEquals("http://localhost:8090/cb/demo/app?code=test-code&state=test-state", finalUrl)
    }

    // VerifyOtpResponse

    @Test
    fun decodeFromString_shallParseStatusField() {
        val response = json.decodeFromString<VerifyOtpResponse>("""{"status":"bound"}""")

        assertEquals("bound", response.status)
    }

    @Test
    fun decodeFromString_shallFail_whenStatusMissing() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<VerifyOtpResponse>("""{}""")
        }
    }

    @Test
    fun encodeToString_shallProduceStatusField() {
        val response = VerifyOtpResponse(status = "bound")

        val encoded = json.encodeToString(VerifyOtpResponse.serializer(), response)

        assertEquals("""{"status":"bound"}""", encoded)
    }

    @Test
    fun equals_shallHoldForDataClassWithSameStatus() {
        val a = VerifyOtpResponse(status = "bound")
        val b = VerifyOtpResponse(status = "bound")

        assertEquals(a, b)
    }

    @Test
    fun decodeFromString_shallParseChallengeTypeAndEmailHint() {
        val response = json.decodeFromString<BindEmailResponse>(
            """{"challenge_type":"email_otp","email_hint":"w*@e*.com"}""",
        )

        assertEquals("email_otp", response.challengeType)
        assertEquals("w*@e*.com", response.emailHint)
    }

    @Test
    fun decodeFromString_shallDefaultEmailHintToEmptyString_whenFieldMissing() {
        val response = json.decodeFromString<BindEmailResponse>(
            """{"challenge_type":"email_otp"}""",
        )

        assertEquals("", response.emailHint)
    }

    @Test
    fun decodeFromString_shallAllowExplicitNullEmailHint() {
        val response = json.decodeFromString<BindEmailResponse>(
            """{"challenge_type":"email_otp","email_hint":null}""",
        )

        assertNull(response.emailHint)
    }

    @Test
    fun decodeFromString_shallFail_whenChallengeTypeMissing() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<BindEmailResponse>("""{"email_hint":"w*@e*.com"}""")
        }
    }

    @Test
    fun decodeFromString_shallIgnoreUnknownFields() {
        val response = json.decodeFromString<BindEmailResponse>(
            """{"challenge_type":"email_otp","email_hint":"w*@e*.com","extra_field":"ignored"}""",
        )

        assertEquals("email_otp", response.challengeType)
        assertEquals("w*@e*.com", response.emailHint)
    }

    @Test
    fun encodeToString_shallProduceBothFields() {
        val response = BindEmailResponse(challengeType = "email_otp", emailHint = "w*@e*.com")

        val encoded = json.encodeToString(BindEmailResponse.serializer(), response)

        assertEquals("""{"challenge_type":"email_otp","email_hint":"w*@e*.com"}""", encoded)
    }

    @Test
    fun equals_shallHoldForDataClassWithSameFields() {
        val a = BindEmailResponse(challengeType = "email_otp", emailHint = "w*@e*.com")
        val b = BindEmailResponse(challengeType = "email_otp", emailHint = "w*@e*.com")

        assertEquals(a, b)
    }
}
