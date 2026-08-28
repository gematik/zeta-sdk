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

import de.gematik.zeta.sdk.configuration.ConfigurationApi
import de.gematik.zeta.sdk.configuration.DiscoveryFetchResult
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ConfigurationHandler].
 */
class ConfigurationHandlerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun canHandle_onlyConfigurationFiles() {
        // Arrange
        val h = ConfigurationHandler(FakeApi())

        // Act & Assert
        assertTrue(h.canHandle(FlowNeed.ConfigurationFiles))
    }

    @Test
    fun canHandle_only_for_ConfigurationFiles() {
        val h = ConfigurationHandler(FakeApi(), FakeValidator())
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
        ctx.configurationStorage.saveProtectedResource(Json.encodeToString(protectedResourceMetadata), maxAgeSeconds = 10)
        ctx.configurationStorage.linkResourceToAuthorizationServer(authServer)
        val h = ConfigurationHandler(FakeApi(), FakeValidator())

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

        val h = ConfigurationHandler(api, FakeValidator())
        val ctx = getDummyFlowContext()
        ctx.configurationStorage.saveProtectedResource(Json.encodeToString(protectedResourceMetadata), maxAgeSeconds = 10)

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

        val h = ConfigurationHandler(api, FakeValidator())
        val ctx = getDummyFlowContext()
        ctx.configurationStorage.linkResourceToAuthorizationServer(asMetadata)

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
        val h = ConfigurationHandler(api, FakeValidator())
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
        val h = ConfigurationHandler(api, FakeValidator())

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.AuthorizationServerMissing> {
            h.handle(FlowNeed.ConfigurationFiles, getDummyFlowContext())
        }
        assertTrue(ex.message!!.contains("No authorization_servers"))
    }

    @Test
    fun handle_throwsValidationException_whenSchemaValidationFails() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson = mapOf(
                resource to Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = false)
        val h = ConfigurationHandler(api, validator)
        val ctx = getDummyFlowContext()

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Failed to validate"))
    }

    @Test
    fun handle_throwsException_whenValidationThrowsAnException() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = FakeApi(
            resJson = mapOf(
                resource to Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(resource))),
            ),
            authJson = mapOf(resource to Json.encodeToString(getDummyAuthServerObject(resource))),
        )
        val validator = FakeValidator(isValid = true, throwsException = true)
        val h = ConfigurationHandler(api, validator)
        val ctx = getDummyFlowContext()

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Validation threw for"))
    }

    @Test
    fun handle_throwsException_whenAuthServerMetadataMissingRegistrationEndpoint() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val invalidAuthJson = """
        {
            "issuer": "$issuer",
            "authorization_endpoint": "$issuer/auth",
            "token_endpoint": "$issuer/token",
            "nonce_endpoint": "$issuer/nonce",
            "jwks_uri": "$issuer/jwks",
            "scopes_supported": ["zero:audience"],
            "response_types_supported": ["code"],
            "grant_types_supported": ["authorization_code"],
            "token_endpoint_auth_methods_supported": ["private_key_jwt"],
            "token_endpoint_auth_signing_alg_values_supported": ["ES256"],
            "code_challenge_methods_supported": ["S256"]
        }
        """.trimIndent()
        val api = FakeApi(
            resJson = mapOf(resource to Json.encodeToString(getDummyProtectedResourceObject(resource, listOf(issuer)))),
            authJson = mapOf(issuer to invalidAuthJson),
        )

        val h = ConfigurationHandler(api)
        val ctx = getDummyFlowContext()

        assertFailsWith<ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
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
        val validator = FakeValidator(isValid = true, 0, false)
        val h = ConfigurationHandler(api, validator)
        val ctx = getDummyFlowContext("missing_scope")

        // Act & Assert
        val ex = assertFailsWith<ConfigurationError.ScopesNotSupported> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }
        assertTrue(ex.message!!.contains("Scopes are not supported by server: [missing_scope]"))
    }

    @Test
    fun handle_fetchesProtectedResource_withCachedETag() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val cachedETag = "\"pr-etag\""
        val ctx = getDummyFlowContext()

        ctx.configurationStorage.saveProtectedResource(
            protectedRes = Json.encodeToString(
                getDummyProtectedResourceObject(resource, listOf(issuer)),
            ),
            maxAgeSeconds = -1,
            eTag = cachedETag,
        )

        val api = RecordingConfigurationApi(
            resourceResult = DiscoveryFetchResult(
                body = Json.encodeToString(
                    getDummyProtectedResourceObject(resource, listOf(issuer)),
                ),
                maxAgeSeconds = 120,
                eTag = "\"new-pr-etag\"",
                notModified = false,
            ),
            authorizationResult = DiscoveryFetchResult(
                body = Json.encodeToString(getDummyAuthServerObject(issuer)),
                maxAgeSeconds = 120,
                eTag = "\"as-etag\"",
                notModified = false,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
        assertEquals(cachedETag, api.lastResourceETag)
        assertEquals(resource, api.lastResourceUrl)
    }

    @Test
    fun handle_throwsValidationFailed_whenResourceReturns304ButCacheIsMissing() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = RecordingConfigurationApi(
            resourceResult = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 300,
                eTag = "\"pr-etag\"",
                notModified = true,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act / Assert
        val error = assertFailsWith<ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, getDummyFlowContext())
        }

        assertTrue(
            error.message.orEmpty().contains(
                "Protected resource metadata missing after 304 revalidation",
            ),
        )
    }

    @Test
    fun handle_throwsWhenProtectedResourceResponseHasNoBody_andIsNot304() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val api = RecordingConfigurationApi(
            resourceResult = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 120,
                eTag = "\"pr-etag\"",
                notModified = false,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act / Assert
        val error = assertFailsWith<IllegalArgumentException> {
            h.handle(FlowNeed.ConfigurationFiles, getDummyFlowContext())
        }

        assertTrue(
            error.message.orEmpty().contains(
                "Missing body for 200 OK discovery response",
            ),
        )
    }

    @Test
    fun handle_savesProtectedResource_withResponseTtlAndETag() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val ctx = getDummyFlowContext()
        val api = RecordingConfigurationApi(
            resourceResult = DiscoveryFetchResult(
                body = Json.encodeToString(
                    getDummyProtectedResourceObject(resource, listOf(issuer)),
                ),
                maxAgeSeconds = 123,
                eTag = "\"saved-pr-etag\"",
                notModified = false,
            ),
            authorizationResult = DiscoveryFetchResult(
                body = Json.encodeToString(getDummyAuthServerObject(issuer)),
                maxAgeSeconds = 456,
                eTag = "\"saved-as-etag\"",
                notModified = false,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
        assertEquals(
            "\"saved-pr-etag\"",
            ctx.configurationStorage.getProtectedResourceETag(),
        )
        assertNotNull(ctx.configurationStorage.getProtectedResource())
    }

    @Test
    fun handle_fetchesAuthorizationServer_withCachedETag() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val authFqdn = "auth.example.com"
        val cachedETag = "\"as-etag\""
        val ctx = getDummyFlowContext()

        // PR is fresh, AS is stale but retains its ETag.
        ctx.configurationStorage.saveProtectedResource(
            protectedRes = Json.encodeToString(
                getDummyProtectedResourceObject(resource, listOf(issuer)),
            ),
            maxAgeSeconds = 100,
        )
        val asMetadata = getDummyAuthServerObject(issuer)
        ctx.configurationStorage.saveAuthServer(
            metadata = asMetadata,
            maxAgeSeconds = -1,
            eTag = cachedETag,
        )
        ctx.configurationStorage.linkResourceToAuthorizationServer(asMetadata)

        val api = RecordingConfigurationApi(
            authorizationResult = DiscoveryFetchResult(
                body = Json.encodeToString(asMetadata),
                maxAgeSeconds = 600,
                eTag = "\"new-as-etag\"",
                notModified = false,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
        assertEquals(cachedETag, api.lastAuthorizationETag)
        assertEquals(issuer, api.lastAuthorizationFqdn)
        assertEquals(
            "\"new-as-etag\"",
            ctx.configurationStorage.getAuthServerETag(authFqdn),
        )
    }

    @Test
    fun handle_touchesAuthorizationServer_whenAuthorizationServerReturns304() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val authFqdn = "auth.example.com"
        val ctx = getDummyFlowContext()

        ctx.configurationStorage.saveProtectedResource(
            protectedRes = Json.encodeToString(
                getDummyProtectedResourceObject(resource, listOf(issuer)),
            ),
            maxAgeSeconds = 100,
        )

        val asMetadata = getDummyAuthServerObject(issuer)
        ctx.configurationStorage.saveAuthServer(
            metadata = asMetadata,
            maxAgeSeconds = -1,
            eTag = "\"old-as-etag\"",
        )
        ctx.configurationStorage.linkResourceToAuthorizationServer(asMetadata)

        val api = RecordingConfigurationApi(
            authorizationResult = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 600,
                eTag = "\"new-as-etag\"",
                notModified = true,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
        assertEquals("\"old-as-etag\"", api.lastAuthorizationETag)

        assertNotNull(ctx.configurationStorage.getAuthServer())
        assertEquals(
            "\"new-as-etag\"",
            ctx.configurationStorage.getAuthServerETag(authFqdn),
        )
    }

    @Test
    fun handle_throwsValidationFailed_whenAuthorizationServerReturns304ButCacheIsMissing() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val ctx = getDummyFlowContext()

        ctx.configurationStorage.saveProtectedResource(
            protectedRes = Json.encodeToString(
                getDummyProtectedResourceObject(resource, listOf(issuer)),
            ),
            maxAgeSeconds = 100,
        )

        val api = RecordingConfigurationApi(
            authorizationResult = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 600,
                eTag = "\"as-etag\"",
                notModified = true,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act / Assert
        val error = assertFailsWith<ConfigurationError.ValidationFailed> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "Auth server metadata missing after 304 revalidation",
            ),
        )
    }

    @Test
    fun handle_throwsWhenAuthorizationServerResponseHasNoBody_andIsNot304() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val ctx = getDummyFlowContext()

        ctx.configurationStorage.saveProtectedResource(
            protectedRes = Json.encodeToString(
                getDummyProtectedResourceObject(resource, listOf(issuer)),
            ),
            maxAgeSeconds = 100,
        )

        val api = RecordingConfigurationApi(
            authorizationResult = DiscoveryFetchResult(
                body = null,
                maxAgeSeconds = 600,
                eTag = "\"as-etag\"",
                notModified = false,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act / Assert
        val error = assertFailsWith<IllegalArgumentException> {
            h.handle(FlowNeed.ConfigurationFiles, ctx)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "Missing body for 200 OK discovery response",
            ),
        )
    }

    @Test
    fun handle_savesAuthorizationServer_withResponseTtlAndETag() = runTest {
        // Arrange
        val resource = "https://api.example.com"
        val issuer = "https://auth.example.com"
        val authFqdn = "auth.example.com"
        val ctx = getDummyFlowContext()

        ctx.configurationStorage.saveProtectedResource(
            protectedRes = Json.encodeToString(
                getDummyProtectedResourceObject(resource, listOf(issuer)),
            ),
            maxAgeSeconds = 100,
        )

        val api = RecordingConfigurationApi(
            authorizationResult = DiscoveryFetchResult(
                body = Json.encodeToString(getDummyAuthServerObject(issuer)),
                maxAgeSeconds = 777,
                eTag = "\"saved-as-etag\"",
                notModified = false,
            ),
        )
        val h = ConfigurationHandler(api, FakeValidator())

        // Act
        val result = h.handle(FlowNeed.ConfigurationFiles, ctx)

        // Assert
        assertEquals(CapabilityResult.Done, result)
        assertEquals(
            "\"saved-as-etag\"",
            ctx.configurationStorage.getAuthServerETag(authFqdn),
        )
    }

    @Test
    fun deserializesRequiredFields() {
        val metadata = json.decodeFromString<AuthorizationServerMetadata>(minimalJson())
        assertEquals("https://auth.example.com", metadata.issuer)
        assertEquals("https://auth.example.com/auth", metadata.authorizationEndpoint)
        assertEquals("https://auth.example.com/token", metadata.tokenEndpoint)
        assertEquals("https://auth.example.com/nonce", metadata.nonceEndpoint)
        assertEquals("https://auth.example.com/register", metadata.registrationEndpoint)
        assertEquals("https://auth.example.com/jwks", metadata.jwksUri)
        assertEquals(listOf("zero:audience"), metadata.scopesSupported)
        assertEquals(listOf("code"), metadata.responseTypesSupported)
        assertEquals(listOf("authorization_code"), metadata.grantTypesSupported)
        assertEquals(listOf("private_key_jwt"), metadata.tokenEndpointAuthMethodsSupported)
        assertEquals(listOf("ES256"), metadata.tokenEndpointAuthSigningAlgValuesSupported)
        assertEquals(listOf("S256"), metadata.codeChallengeMethodsSupported)
    }

    @Test
    fun optionalFieldsAreNullWhenAbsent() {
        val metadata = json.decodeFromString<AuthorizationServerMetadata>(minimalJson())
        assertNull(metadata.openidProvidersEndpoint)
        assertNull(metadata.responseModesSupported)
        assertNull(metadata.serviceDocumentation)
        assertNull(metadata.uiLocalesSupported)
    }

    @Test
    fun deserializesOptionalFields() {
        val metadata = json.decodeFromString<AuthorizationServerMetadata>(fullJson())
        assertEquals("https://auth.example.com/openid", metadata.openidProvidersEndpoint)
        assertEquals(listOf("query"), metadata.responseModesSupported)
        assertEquals("https://auth.example.com/docs", metadata.serviceDocumentation)
        assertEquals(listOf("en", "de"), metadata.uiLocalesSupported)
    }

    @Test
    fun ignoresUnknownFields() {
        val withExtra = minimalJson().dropLast(1) + """, "unknown_field": "ignored" }"""
        val metadata = json.decodeFromString<AuthorizationServerMetadata>(withExtra)
        assertEquals("https://auth.example.com", metadata.issuer)
    }

    @Test
    fun fallsBackToOpenidProvidersEndpointWhenRegistrationEndpointMissing() {
        val legacyJson = minimalJson()
            .replace(
                """"registration_endpoint": "https://auth.example.com/register",""",
                """
            "openid_providers_endpoint": "https://auth.example.com/openid",
                """.trimIndent(),
            )

        val metadata = Json.decodeFromString<AuthorizationServerMetadata>(legacyJson)

        assertEquals(
            "https://auth.example.com/openid",
            metadata.effectiveRegistrationEndpoint,
        )
    }

    @Test
    fun failsWhenIssuerMissing() {
        val withoutIssuer = minimalJson()
            .replace(""""issuer": "https://auth.example.com",""", "")
        assertFailsWith<SerializationException> {
            Json.decodeFromString<AuthorizationServerMetadata>(withoutIssuer)
        }
    }

    private class RecordingConfigurationApi(
        var resourceResult: DiscoveryFetchResult = DiscoveryFetchResult(
            body = null,
            maxAgeSeconds = null,
            eTag = null,
            notModified = false,
        ),
        var authorizationResult: DiscoveryFetchResult = DiscoveryFetchResult(
            body = null,
            maxAgeSeconds = null,
            eTag = null,
            notModified = false,
        ),
    ) : ConfigurationApi {
        var resourceFetchCalls: Int = 0
            private set
        var authorizationFetchCalls: Int = 0
            private set

        var lastResourceUrl: String? = null
            private set
        var lastResourceSubpath: String? = null
            private set
        var lastResourceETag: String? = null
            private set

        var lastAuthorizationFqdn: String? = null
            private set
        var lastAuthorizationETag: String? = null
            private set

        override suspend fun fetchResourceMetadata(
            resourceUrl: String,
            subpath: String?,
            eTag: String?,
        ): DiscoveryFetchResult {
            resourceFetchCalls++
            lastResourceUrl = resourceUrl
            lastResourceSubpath = subpath
            lastResourceETag = eTag
            return resourceResult
        }

        override suspend fun fetchAuthorizationMetadata(
            authFqdns: String,
            eTag: String?,
        ): DiscoveryFetchResult {
            authorizationFetchCalls++
            lastAuthorizationFqdn = authFqdns
            lastAuthorizationETag = eTag
            return authorizationResult
        }

        override suspend fun getResourceSchema(): String = "{}"

        override suspend fun getAuthorizationSchema(): String = "{}"
    }

    private fun minimalJson() = """
        {
            "issuer": "https://auth.example.com",
            "authorization_endpoint": "https://auth.example.com/auth",
            "token_endpoint": "https://auth.example.com/token",
            "nonce_endpoint": "https://auth.example.com/nonce",
            "registration_endpoint": "https://auth.example.com/register",
            "jwks_uri": "https://auth.example.com/jwks",
            "scopes_supported": ["zero:audience"],
            "response_types_supported": ["code"],
            "grant_types_supported": ["authorization_code"],
            "token_endpoint_auth_methods_supported": ["private_key_jwt"],
            "token_endpoint_auth_signing_alg_values_supported": ["ES256"],
            "code_challenge_methods_supported": ["S256"]
        }
    """.trimIndent()

    private fun fullJson() = """
        {
            "issuer": "https://auth.example.com",
            "authorization_endpoint": "https://auth.example.com/auth",
            "token_endpoint": "https://auth.example.com/token",
            "nonce_endpoint": "https://auth.example.com/nonce",
            "registration_endpoint": "https://auth.example.com/register",
            "openid_providers_endpoint": "https://auth.example.com/openid",
            "jwks_uri": "https://auth.example.com/jwks",
            "scopes_supported": ["zero:audience"],
            "response_types_supported": ["code", "token", "id_token"],
            "response_modes_supported": ["query"],
            "grant_types_supported": ["authorization_code", "refresh_token"],
            "token_endpoint_auth_methods_supported": ["private_key_jwt"],
            "token_endpoint_auth_signing_alg_values_supported": ["ES256"],
            "service_documentation": "https://auth.example.com/docs",
            "ui_locales_supported": ["en", "de"],
            "code_challenge_methods_supported": ["S256"]
        }
    """.trimIndent()
}
