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

import de.gematik.zeta.sdk.configuration.ConfigurationApi
import de.gematik.zeta.sdk.configuration.WellKnownSchemaValidation
import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.ApiVersionStatus
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.BearerMethod
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.configuration.models.ZetaAslUse
import de.gematik.zeta.sdk.flow.RequestEvaluatorImplTest.FakeForwardingClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.SdkStorage
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.json.Json
import kotlin.collections.Map

/**
 * Test util.
 */
class RecordingDoneHandler(
    private val needToHandle: FlowNeed,
) : CapabilityHandler {
    var called: Boolean = false

    override fun canHandle(need: FlowNeed): Boolean = need == needToHandle

    override suspend fun handle(
        need: FlowNeed,
        ctx: FlowContext,
    ): CapabilityResult = CapabilityResult.Done.also { called = true }
}

/**
 * Test util.
 */
class FakeApi(
    private val authJson: Map<String, String> = mapOf(),
    private val resJson: Map<String, String> = mapOf(),
    private val authSchema: String = "",
    private val resSchema: String = "",
) : ConfigurationApi {
    override suspend fun fetchResourceMetadata(resourceUrl: String): String = resJson[resourceUrl] ?: error("unknown resource $resourceUrl")
    override suspend fun fetchAuthorizationMetadata(authFqdns: String) = authJson[authFqdns] ?: error("unknown issuer $authFqdns")
    override suspend fun getResourceSchema(): String = resSchema
    override suspend fun getAuthorizationSchema(): String = authSchema
}

/**
 * Test util.
 */
fun getDummyFlowContext(): FlowContext =
    FlowContextImpl(
        "https://api.example.com",
        object : ForwardingClient {
            override suspend fun executeOnce(builder: HttpRequestBuilder): ZetaHttpResponse {
                TODO("Not need for test")
            }
        },
        InMemoryStorage(),
    )

/**
 * Test util.
 */
class FakeValidator(
    private var isValid: Boolean = true,
    private val failAfter: Int = 0,
    private val throwsException: Boolean = false,
) : WellKnownSchemaValidation {
    private var count: Int = 0

    override suspend fun validate(
        resource: String,
        schema: String,
    ): Boolean {
        if (throwsException) {
            error("Unhandled")
        }
        if (failAfter > 0) {
            isValid = !(count == failAfter)
            count++
        }
        return isValid
    }
}

/**
 * Test util.
 */
fun getDummyAuthServerObject(
    issuer: String = "",
    token_endpoint: String = "",
    openidProvidersEndpoint: String = "",
): AuthorizationServerMetadata =
    AuthorizationServerMetadata(
        issuer = issuer,
        authorizationEndpoint = "",
        tokenEndpoint = token_endpoint,
        nonceEndpoint = "",
        openidProvidersEndpoint = openidProvidersEndpoint,
        jwksUri = "",
        scopesSupported = listOf(""),
        responseTypesSupported = listOf("TOKEN"),
        responseModesSupported = listOf(""),
        grantTypesSupported = listOf(""),
        tokenEndpointAuthMethodsSupported = listOf(""),
        tokenEndpointAuthSigningAlgValuesSupported = listOf(""),
        serviceDocumentation = "",
        uiLocalesSupported = listOf(""),
        codeChallengeMethodsSupported = listOf(""),
        apiVersionsSupported =
        listOf(
            ApiVersion(
                majorVersion = 1,
                version = "",
                status = ApiVersionStatus.STABLE,
                documentationUri = "",
            ),
        ),
    )

/**
 * Test util.
 */
fun getDummyProtectedResourceObject(
    resource: String = "",
    authorizationServers: List<String> = emptyList(),
): ProtectedResourceMetadata =
    ProtectedResourceMetadata(
        resource = resource,
        authorizationServers = authorizationServers,
        zetaAslUse = ZetaAslUse.NOT_SUPPORTED,
        jwksUri = "",
        scopesSupported = listOf(""),
        bearerMethodsSupported = listOf(BearerMethod.HEADER, BearerMethod.BODY),
        resourceSigningAlgValuesSupported = listOf(""),
        resourceName = "",
        resourceDocumentation = "",
        resourcePolicyUri = "",
        resourceTosUri = "",
        tlsClientCertificateBoundAccessTokens = true,
        authorizationDetailsTypesSupported = listOf(""),
        dpopSigningAlgValuesSupported = listOf(""),
        dpopBoundAccessTokensRequired = true,
        signedMetadata = "",
        apiVersionsSupported =
        listOf(
            ApiVersion(
                majorVersion = 1,
                version = "",
                status = ApiVersionStatus.STABLE,
                documentationUri = "",
            ),
            ApiVersion(
                majorVersion = 2,
                version = "",
                status = ApiVersionStatus.BETA,
            ),
        ),
    )

suspend fun getDummyContextWithResource(fwdClient: ForwardingClient = FakeForwardingClient(), storage: SdkStorage = InMemoryStorage()): FlowContext {
    val ctx = FlowContextImpl("test", fwdClient, storage)
    val good = getDummyProtectedResourceObject("test", listOf("https://auth.example.com"))
    ctx.configurationStorage.saveProtectedResource(Json.encodeToString(good))

    val authServer = getDummyAuthServerObject(openidProvidersEndpoint = "test", issuer = "issuer")
    ctx.configurationStorage.linkResourceToAuthorizationServer("test", authServer)

    return ctx
}
