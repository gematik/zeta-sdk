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

package de.gematik.zeta.sdk.configuration

import de.gematik.zeta.sdk.configuration.models.ApiVersion
import de.gematik.zeta.sdk.configuration.models.ApiVersionStatus
import de.gematik.zeta.sdk.configuration.models.AuthorizationServerMetadata
import de.gematik.zeta.sdk.configuration.models.BearerMethod
import de.gematik.zeta.sdk.configuration.models.ProtectedResourceMetadata
import de.gematik.zeta.sdk.configuration.models.ZetaAslUse
import kotlinx.serialization.json.Json

/**
 * Test util.
 */
fun getDummyProtectedResourceObject(
    resource: String = "",
    authorizationServers: List<String> = emptyList(),

): String =
    Json.encodeToString(
        ProtectedResourceMetadata(
            resource = resource,
            authorizationServers = authorizationServers,
            zetaAslUse = ZetaAslUse.REQUIRED,
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
        ),
    )

/**
 * Test util.
 */
fun getDummyAuthServerObject(
    issuer: String = "",
    token_endpoint: String = "",
): AuthorizationServerMetadata =
    AuthorizationServerMetadata(
        issuer = issuer,
        authorizationEndpoint = "",
        tokenEndpoint = token_endpoint,
        nonceEndpoint = "",
        openidProvidersEndpoint = "",
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
