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

package de.gematik.zeta.sdk.authentication
import de.gematik.zeta.sdk.authentication.AccessTokenProviderImpl.Companion.applyHtuRules
import de.gematik.zeta.sdk.authentication.AccessTokenProviderImpl.Companion.toHttpsForWsDpop
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessTokenProviderExtensionTest {
    @Test
    fun applyHtuRules_removesQueryParameters() {
        // Arrange
        val input = "https://example.com/path?param=value"
        val expected = "https://example.com/path"

        // Act & Assert
        assertEquals(expected, input.applyHtuRules())
    }

    @Test
    fun applyHtuRules_RemovesFragment() {
        // Arrange
        val input = "https://example.com/path#fragment"
        val expected = "https://example.com/path"

        // Act & Assert
        assertEquals(expected, input.applyHtuRules())
    }

    @Test
    fun applyHtuRulesRemovesQueryAndFragment() {
        // Arrange
        val input = "https://example.com/path?param=value#fragment"
        val expected = "https://example.com/path"

        // Act & Assert
        assertEquals(expected, input.applyHtuRules())
    }

    @Test
    fun applyHtuRules_convertsWssToHttps() {
        // Arrange
        val input = "wss://example.com/ws"
        val expected = "https://example.com/ws"

        // Act & Assert
        assertEquals(expected, input.applyHtuRules())
    }

    @Test
    fun applyHtuRules_ConvertsWsToHttp() {
        // Arrange
        val input = "ws://localhost:8080/ws"
        val expected = "http://localhost:8080/ws"

        // Act & Assert
        assertEquals(expected, input.applyHtuRules())
    }

    @Test
    fun applyHtuRules_convertsWssToHttpsAndRemovesQuery() {
        // Arrange
        val input = "wss://example.com/ws?token=abc123"
        val expected = "https://example.com/ws"

        // Act & Assert
        assertEquals(expected, input.applyHtuRules())
    }

    @Test
    fun applyHtuRules_withCompleteWebsocketUrl() {
        // Arrange
        val input = "wss://api.example.com:8080/socket/path?session=xyz#room1"
        val expected = "https://api.example.com:8080/socket/path"

        // Act & Assert
        assertEquals(expected, input.applyHtuRules())
    }

    @Test
    fun toHttpsForWsDpop_convertsWssToHttps() {
        // Arrange
        val input = "wss://example.com/ws"
        val expected = "https://example.com/ws"

        // Act & Assert
        assertEquals(expected, input.toHttpsForWsDpop())
    }

    @Test
    fun toHttpsForWsDpop_convertsWsToHttp() {
        // Arrange
        val input = "ws://localhost:8080/ws"
        val expected = "http://localhost:8080/ws"

        // Act & Assert
        assertEquals(expected, input.toHttpsForWsDpop())
    }
}
