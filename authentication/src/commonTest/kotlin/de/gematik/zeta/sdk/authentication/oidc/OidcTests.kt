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

private val fakeOtpCallback = object : OtpCallback {
    override suspend fun awaitEmail(): String = "test@example.com"

    override suspend fun awaitOtp(
        emailHint: String?,
        rejected: Boolean,
    ): OtpSubmission = OtpSubmission.Otp("123456")
}

class OidcTests {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestUriApp_shallAppendAppSuffixToRequestUri() {
        val config = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo",
            otpCallback = fakeOtpCallback,
        )

        assertEquals("http://localhost:8090/cb/demo/app", config.requestUriApp)
    }

    @Test
    fun requestUriOidc_shallAppendOidcSuffixToRequestUri() {
        val config = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo",
            otpCallback = fakeOtpCallback,
        )

        assertEquals("http://localhost:8090/cb/demo/oidc", config.requestUriOidc)
    }

    @Test
    fun requestUriApp_and_requestUriOidc_shallNeverBeEqual() {
        val config = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo",
            otpCallback = fakeOtpCallback,
        )

        assertNotEquals(config.requestUriApp, config.requestUriOidc)
    }

    @Test
    fun requestUriApp_shallReflectChangesToRequestUri_forDifferentInstances() {
        val configA = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo",
            otpCallback = fakeOtpCallback,
        )
        val configB = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "https://my.client.local/cb/otherapp",
            otpCallback = fakeOtpCallback,
        )

        assertEquals("http://localhost:8090/cb/demo/app", configA.requestUriApp)
        assertEquals("https://my.client.local/cb/otherapp/app", configB.requestUriApp)
    }

    @Test
    fun authenticationCallback_shallDefaultToNull_whenNotProvided() {
        val config = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo",
            otpCallback = fakeOtpCallback,
        )

        assertNull(config.authenticationCallback)
    }

    @Test
    fun equals_shallHoldForDataClassWithIdenticalConstructorArgs() {
        val configA = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo",
            otpCallback = fakeOtpCallback,
        )
        val configB = configA.copy()

        assertEquals(configA, configB)
        assertEquals(configA.requestUriApp, configB.requestUriApp)
        assertEquals(configA.requestUriOidc, configB.requestUriOidc)
    }

    @Test
    fun requestUri_withTrailingSlash_shallProduceDoubleSlashInDerivedUris() {
        val config = OidcConfig(
            idpIss = "https://sekidp.example.org",
            idpAlias = "sekidp-oidc",
            requestUri = "http://localhost:8090/cb/demo/",
            otpCallback = fakeOtpCallback,
        )

        assertEquals("http://localhost:8090/cb/demo//app", config.requestUriApp)
    }

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
