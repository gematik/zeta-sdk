/*
 *
 *  * #%L
 *  * ZETA-Client
 *  * %%
 *  * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 *  * %%
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *  *
 *  * ******
 *  *
 *  * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 *  * #L%
 *
 */

package de.gematik.zeta.sdk.authentication.oidc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OidcSerializationTests {

    private val json = Json

    private fun keysOf(encoded: String): Set<String> =
        Json.parseToJsonElement(encoded).jsonObject.keys

    @Test
    fun oidcTokenRequest_serializesEveryFieldToItsSpecName_whenAllFieldsPresent() {
        // Arrange
        val request = OidcTokenIssuance.OidcTokenRequest(
            grantType = "authorization_code",
            clientId = "client-id",
            requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
            clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            clientAssertion = "assertion",
            scope = "openid",
            appRedirectUri = "http://localhost:8090/cb/demo/app",
            nonce = "nonce-value",
            code = "auth-code",
            codeVerifier = "verifier",
            audience = "https://zeta-kind.local/pep/",
            subjectToken = "subject-token",
            subjectTokenType = "urn:ietf:params:oauth:token-type:access_token",
        )

        // Act
        val encoded = json.encodeToString(OidcTokenIssuance.OidcTokenRequest.serializer(), request)

        // Assert
        assertEquals(
            setOf(
                "grant_type",
                "client_id",
                "requested_token_type",
                "client_assertion_type",
                "client_assertion",
                "scope",
                "redirect_uri",
                "nonce",
                "code",
                "code_verifier",
                "audience",
                "subject_token",
                "subject_token_type",
            ),
            keysOf(encoded),
        )
    }

    @Test
    fun oidcTokenRequest_omitsOptionalFields_whenTheyAreNull() {
        val request = OidcTokenIssuance.OidcTokenRequest(
            grantType = "urn:ietf:params:oauth:grant-type:token-exchange",
            clientId = "client-id",
            requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
            clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            clientAssertion = "assertion",
        )

        // Act
        val encoded = json.encodeToString(OidcTokenIssuance.OidcTokenRequest.serializer(), request)

        // Assert
        assertEquals(
            setOf("grant_type", "client_id", "requested_token_type", "client_assertion_type", "client_assertion"),
            keysOf(encoded),
        )
    }

    @Test
    fun oidcTokenRequest_roundTripsAllFields() {
        // Arrange
        val original = OidcTokenIssuance.OidcTokenRequest(
            grantType = "authorization_code",
            clientId = "client-id",
            requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
            clientAssertionType = "jwt-bearer",
            clientAssertion = "assertion",
            scope = "openid email",
            appRedirectUri = "http://localhost:8090/cb/demo/app",
            nonce = "nonce-value",
            code = "auth-code",
            codeVerifier = "verifier",
            audience = "https://zeta-kind.local/pep/",
            subjectToken = "subject-token",
            subjectTokenType = "access_token",
        )

        // Act
        val decoded = json.decodeFromString(
            OidcTokenIssuance.OidcTokenRequest.serializer(),
            json.encodeToString(OidcTokenIssuance.OidcTokenRequest.serializer(), original),
        )

        // Assert
        assertEquals(original, decoded)
    }

    @Test
    fun oidcTokenRequest_deserializesWithNullOptionals_whenOnlyMandatoryFieldsPresent() {
        val body = """
            {"grant_type":"authorization_code","client_id":"client-id",
            "requested_token_type":"urn:ietf:params:oauth:token-type:refresh_token",
             "client_assertion_type":"jwt-bearer","client_assertion":"assertion"}
        """.trimIndent()

        // Act
        val decoded = json.decodeFromString(OidcTokenIssuance.OidcTokenRequest.serializer(), body)

        // Assert
        assertEquals("authorization_code", decoded.grantType)
        assertNull(decoded.scope)
        assertNull(decoded.appRedirectUri)
        assertNull(decoded.nonce)
        assertNull(decoded.code)
        assertNull(decoded.codeVerifier)
        assertNull(decoded.audience)
        assertNull(decoded.subjectToken)
        assertNull(decoded.subjectTokenType)
    }

    @Test
    fun oidcTokenRequest_throws_whenMandatoryFieldMissing() {
        val body = """{"grant_type":"authorization_code","client_id":"c","client_assertion_type":"jwt-bearer"}"""

        // Act & Assert
        assertFailsWith<Exception> {
            json.decodeFromString(OidcTokenIssuance.OidcTokenRequest.serializer(), body)
        }
    }

    // --- OidcAccessTokenResponse -----------------------------------------------------------------------------

    @Test
    fun oidcAccessTokenResponse_deserializesEverySpecName_whenAllFieldsPresent() {
        val body = """
            {"access_token":"at","expires_in":300,"refresh_expires_in":1800,"token_type":"DPoP",
             "scope":"openid zeta:email-verify","refresh_token":"rt","email_hint":"t*@e*.com",
             "binding_mode":"collect_email"}
        """.trimIndent()

        // Act
        val decoded = json.decodeFromString(OidcAccessTokenResponse.serializer(), body)

        // Assert
        assertEquals("at", decoded.accessToken)
        assertEquals(300, decoded.expiresIn)
        assertEquals(1800, decoded.refreshExpires)
        assertEquals("DPoP", decoded.tokenType)
        assertEquals("openid zeta:email-verify", decoded.scope)
        assertEquals("rt", decoded.refreshToken)
        assertEquals("t*@e*.com", decoded.emailHint)
        assertEquals("collect_email", decoded.bindingMode)
    }

    @Test
    fun oidcAccessTokenResponse_deserializesWithNullOptionals_whenOnlyMandatoryFieldsPresent() {
        val body = """{"access_token":"at","expires_in":300,"token_type":"DPoP","scope":"openid"}"""

        // Act
        val decoded = json.decodeFromString(OidcAccessTokenResponse.serializer(), body)

        // Assert
        assertNull(decoded.refreshExpires)
        assertNull(decoded.refreshToken)
        assertNull(decoded.emailHint)
        assertNull(decoded.bindingMode)
    }

    @Test
    fun oidcAccessTokenResponse_serializesEveryFieldToItsSpecName_whenAllFieldsPresent() {
        val response = OidcAccessTokenResponse(
            accessToken = "at",
            expiresIn = 300,
            refreshExpires = 1800,
            tokenType = "DPoP",
            scope = "openid",
            refreshToken = "rt",
            emailHint = "t*@e*.com",
            bindingMode = "collect_email",
        )

        // Act
        val encoded = json.encodeToString(OidcAccessTokenResponse.serializer(), response)

        // Assert
        assertEquals(
            setOf(
                "access_token",
                "expires_in",
                "refresh_expires_in",
                "token_type",
                "scope",
                "refresh_token",
                "email_hint",
                "binding_mode",
            ),
            keysOf(encoded),
        )
    }

    @Test
    fun oidcAccessTokenResponse_omitsOptionalFields_whenTheyAreNull() {
        val response = OidcAccessTokenResponse(
            accessToken = "at",
            expiresIn = 300,
            tokenType = "DPoP",
            scope = "openid",
        )

        // Act
        val encoded = json.encodeToString(OidcAccessTokenResponse.serializer(), response)

        // Assert
        assertEquals(setOf("access_token", "expires_in", "token_type", "scope"), keysOf(encoded))
    }

    @Test
    fun oidcAccessTokenResponse_throws_whenMandatoryFieldMissing() {
        // Arrange - scope is mandatory and absent
        val body = """{"access_token":"at","expires_in":300,"token_type":"DPoP"}"""

        // Act & Assert
        assertFailsWith<Exception> {
            json.decodeFromString(OidcAccessTokenResponse.serializer(), body)
        }
    }

    @Test
    fun oidcErrorResponse_roundTripsBothFields_whenDescriptionPresent() {
        val original = OidcErrorResponse(error = "invalid_request", errorDescription = "missing code_verifier")

        // Act
        val encoded = json.encodeToString(OidcErrorResponse.serializer(), original)
        val decoded = json.decodeFromString(OidcErrorResponse.serializer(), encoded)

        // Assert
        assertEquals(setOf("error", "error_description"), keysOf(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun oidcErrorResponse_omitsDescription_whenNull() {
        val original = OidcErrorResponse(error = "invalid_client")

        // Act
        val encoded = json.encodeToString(OidcErrorResponse.serializer(), original)

        // Assert
        assertEquals(setOf("error"), keysOf(encoded))
        assertNull(json.decodeFromString(OidcErrorResponse.serializer(), encoded).errorDescription)
    }

    @Test
    fun oidcErrorResponse_throws_whenErrorFieldMissing() {
        val body = """{"error_description":"something went wrong"}"""

        // Act & Assert
        assertFailsWith<Exception> {
            json.decodeFromString(OidcErrorResponse.serializer(), body)
        }
    }

    @Test
    fun parResponse_roundTripsRequestUriAndExpiresIn() {
        val original = ParResponse(requestUri = "urn:ietf:params:oauth:request_uri:abc123", expiresIn = 60)

        // Act
        val encoded = json.encodeToString(ParResponse.serializer(), original)
        val decoded = json.decodeFromString(ParResponse.serializer(), encoded)

        // Assert
        assertEquals(setOf("request_uri", "expires_in"), keysOf(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun parResponse_throws_whenExpiresInMissing() {
        val body = """{"request_uri":"urn:ietf:params:oauth:request_uri:abc123"}"""

        // Act & Assert
        assertFailsWith<Exception> {
            json.decodeFromString(ParResponse.serializer(), body)
        }
    }

    @Test
    fun bindEmailRequest_serializesEmail_whenPresent() {
        val original = BindEmailRequest(email = "user@example.com")

        // Act
        val encoded = json.encodeToString(BindEmailRequest.serializer(), original)
        val decoded = json.decodeFromString(BindEmailRequest.serializer(), encoded)

        // Assert
        assertEquals(setOf("email"), keysOf(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun bindEmailRequest_omitsEmail_whenNull() {
        val original = BindEmailRequest()

        // Act
        val encoded = json.encodeToString(BindEmailRequest.serializer(), original)

        // Assert
        assertTrue(keysOf(encoded).isEmpty())
        assertNull(json.decodeFromString(BindEmailRequest.serializer(), encoded).email)
    }

    @Test
    fun bindEmailRequest_deserializesNullEmail_whenKeyAbsent() {
        val body = "{}"

        // Act
        val decoded = json.decodeFromString(BindEmailRequest.serializer(), body)

        // Assert
        assertNull(decoded.email)
    }

    // --- OtpVerifyRequest ------------------------------------------------------------------------------------

    @Test
    fun otpVerifyRequest_roundTripsOtp() {
        val original = OtpVerifyRequest(otp = "123456")

        // Act
        val encoded = json.encodeToString(OtpVerifyRequest.serializer(), original)
        val decoded = json.decodeFromString(OtpVerifyRequest.serializer(), encoded)

        // Assert
        assertEquals(setOf("otp"), keysOf(encoded))
        assertEquals("123456", decoded.otp)
    }

    @Test
    fun otpVerifyRequest_throws_whenOtpMissing() {
        val body = "{}"

        // Act & Assert
        assertFailsWith<Exception> {
            json.decodeFromString(OtpVerifyRequest.serializer(), body)
        }
    }

    @Test
    fun verifyOtpResponse_roundTripsStatus() {
        val original = VerifyOtpResponse(status = "bound")

        // Act
        val encoded = json.encodeToString(VerifyOtpResponse.serializer(), original)
        val decoded = json.decodeFromString(VerifyOtpResponse.serializer(), encoded)

        // Assert
        assertEquals(setOf("status"), keysOf(encoded))
        assertEquals(original, decoded)
    }

    @Test
    fun bindEmailResponse_roundTripsChallengeTypeAndEmailHint() {
        val original = BindEmailResponse(challengeType = "email_otp", emailHint = "t*@e*.com")

        // Act
        val encoded = json.encodeToString(BindEmailResponse.serializer(), original)
        val decoded = json.decodeFromString(BindEmailResponse.serializer(), encoded)

        // Assert
        assertEquals(setOf("challenge_type", "email_hint"), keysOf(encoded))
        assertEquals(original, decoded)
    }
}
