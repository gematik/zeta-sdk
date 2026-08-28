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

package de.gematik.zeta.sdk.authentication.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessTokenRequestTest {

    private fun request(
        subjectToken: String? = null,
        subjectTokenType: String? = null,
        refreshToken: String? = null,
    ) = AccessTokenRequest(
        grantType = "refresh_token",
        clientId = "client-id",
        subjectToken = subjectToken,
        subjectTokenType = subjectTokenType,
        requestedTokenType = "urn:ietf:params:oauth:token-type:refresh_token",
        clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        clientAssertion = "assertion",
        scope = "scope1 scope2",
        refreshToken = refreshToken,
        audience = "https://auth.example.com",
    )

    @Test
    fun toParameters_alwaysIncludesMandatoryFields() {
        val sut = request()

        // Act
        val params = sut.toParameters()

        // Assert
        assertEquals("refresh_token", params["grant_type"])
        assertEquals("client-id", params["client_id"])
        assertEquals("urn:ietf:params:oauth:token-type:refresh_token", params["requested_token_type"])
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", params["client_assertion_type"])
        assertEquals("assertion", params["client_assertion"])
        assertEquals("scope1 scope2", params["scope"])
        assertEquals("https://auth.example.com", params["audience"])
    }

    @Test
    fun toParameters_omitsOptionalFields_whenNull() {
        val sut = request(subjectToken = null, subjectTokenType = null, refreshToken = null)

        // Act
        val params = sut.toParameters()

        // Assert
        assertNull(params["subject_token"])
        assertNull(params["subject_token_type"])
        assertNull(params["refresh_token"])
    }

    @Test
    fun toParameters_omitsOptionalFields_whenBlank() {
        val sut = request(subjectToken = "", subjectTokenType = "  ", refreshToken = "")

        // Act
        val params = sut.toParameters()

        // Assert
        assertNull(params["subject_token"])
        assertNull(params["subject_token_type"])
        assertNull(params["refresh_token"])
    }

    @Test
    fun toParameters_includesOptionalFields_whenPresent() {
        val sut = request(
            subjectToken = "subject-token-value",
            subjectTokenType = "urn:ietf:params:oauth:token-type:access_token",
            refreshToken = "refresh-token-value",
        )

        // Act
        val params = sut.toParameters()

        // Assert
        assertEquals("subject-token-value", params["subject_token"])
        assertEquals("urn:ietf:params:oauth:token-type:access_token", params["subject_token_type"])
        assertEquals("refresh-token-value", params["refresh_token"])
    }
}
