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

package de.gematik.zeta.sdk.flow

import de.gematik.zeta.sdk.flow.handler.EnsureAccessTokenHandler
import de.gematik.zeta.sdk.flow.handler.audienceFromIssuer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [EnsureAccessTokenHandler].
 */
class EnsureAccessTokenHandlerTest {
    @Test
    fun audienceFromIssuer_returnsAudienceWithCorrectPath() {
        val expected = "https://example.com/auth/"
        val issuerWithPath = "https://example.com/auth/realms/zeta-guard/"
        val issuerWithAuth = "https://example.com/auth/"
        val issuerNoAuth = "https://example.com/no-auth/"
        val issuerNoPath = "https://example.com/"

        assertEquals(expected, audienceFromIssuer(issuerWithPath))
        assertEquals(expected, audienceFromIssuer(issuerWithAuth))
        assertEquals(expected, audienceFromIssuer(issuerNoAuth))
        assertEquals(expected, audienceFromIssuer(issuerNoPath))
    }
}
