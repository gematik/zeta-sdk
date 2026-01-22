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

import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FakeApi
import de.gematik.zeta.sdk.flow.FakeValidator
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.getDummyAuthServerObject
import de.gematik.zeta.sdk.flow.getDummyFlowContext
import de.gematik.zeta.sdk.flow.getDummyProtectedResourceObject
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler
import de.gematik.zeta.sdk.flow.handler.ConfigurationHandler.ConfigurationError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [ConfigurationHandler].
 */
class ConfigurationHandlerTest {

    @Test
    fun canHandle_onlyConfigurationFiles() {
        // Arrange
        val h = ConfigurationHandler(FakeApi(), createAuthConfig())

        // Act & Assert
        assertTrue(h.canHandle(FlowNeed.ConfigurationFiles))
    }

    @Test
    fun canHandle_only_for_ConfigurationFiles() {
        val h = ConfigurationHandler(FakeApi(), createAuthConfig(), FakeValidator())
        assertTrue(h.canHandle(FlowNeed.ConfigurationFiles))
    }

    @Test
    fun handle_returnsDone_whenMetadataAlreadyAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val protectedResourceMetadata =
            getDummyProtectedResourceObject(resource, listOf("https://auth.example.com"))
        val ctx = getDummyFlowContext()
        val authServer = getDummyAuthServerObject(resource)
        ctx.configurationStorage.saveProtectedResource(Json.encodeToString(protectedResourceMetadata))
        ctx.configurationStorage.linkResourceToAuthorizationServer(resource, authServer)
        val h = ConfigurationHandler(FakeApi(), createAuthConfig(), FakeValidator())

        // Act
        val res = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, res)
    }

    @Test
    fun handle_doesNotFetch_whenCacheForProtectedResourceMetadataIsAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val protectedResourceMetadata = getDummyProtectedResourceObject(resource, listOf(issuer))
        val api = FakeApi(
            authJson = mapOf(issuer to Json.encodeToString(getDummyAuthServerObject(issuer))),
        )

        val h = ConfigurationHandler(api, createAuthConfig(), FakeValidator())
        val ctx = getDummyFlowContext()
        ctx.configurationStorage.saveProtectedResource(Json.encodeToString(protectedResourceMetadata))

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
    }

    @Test
    fun handle_doesNotFetch_whenCacheForAuthorizationMetadataIsAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val asMetadata = getDummyAuthServerObject(issuer)
        val api = FakeApi(
            resJson =
            mapOf(resource to Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(issuer)))),
        )

        val h = ConfigurationHandler(api, createAuthConfig(), FakeValidator())
        val ctx = getDummyFlowContext()
        ctx.configurationStorage.linkResourceToAuthorizationServer(resource, asMetadata)

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
    }

    @Test
    fun handle_fetchesBothWhenNotCachedAvailable() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"

        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(issuer))),
            ),
            authJson = mapOf(issuer to Json.encodeToString(getDummyAuthServerObject(issuer))),
        )
        val h = ConfigurationHandler(api, createAuthConfig(), FakeValidator())
        val ctx = getDummyFlowContext()

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
    }

    @Test
    fun handle_throwsException_whenEmptyAuthorizationServers() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, emptyList())),
            ),
        )
        val h = ConfigurationHandler(api, createAuthConfig(), FakeValidator())

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.AuthorizationServerMissing> {
            h.handle(FlowNeed.ConfigurationFiles, getDummyFlowContext())
        }
        assertTrue(ex.message!!.contains("No authorization_servers"))
    }

    @Test
    @Ignore
    fun handle_throwsValidationException_whenSchemaValidationFails() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = false)
        val h = ConfigurationHandler(api, createAuthConfig(), validator)
        val ctx = getDummyFlowContext()

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Failed to validate"))
    }

    @Test
    @Ignore
    fun handle_throwsException_whenValidationThrowsAnException() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = true, 0, true)
        val h = ConfigurationHandler(api, createAuthConfig(), validator)
        val ctx = getDummyFlowContext()

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Validation threw for"))
    }

    @Test
    fun handle_throwsException_whenScopeValidationThrowsAnException() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson =
            mapOf(
                resource to
                    Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = true, 0, true)
        val h = ConfigurationHandler(api, createAuthConfig(listOf("missing_scope")), validator)
        val ctx = getDummyFlowContext()

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.ScopesNotSupported> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Scopes are not supported by server: [missing_scope]"))
    }

    private fun createAuthConfig(scopes: List<String> = emptyList()) =
        AuthConfig(scopes, 300, true, SmbTokenProvider(SmbTokenProvider.Credentials("", "", "")))
}
