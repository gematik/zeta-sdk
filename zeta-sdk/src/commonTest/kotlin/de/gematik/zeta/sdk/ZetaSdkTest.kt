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

package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.AuthenticationApiImpl
import de.gematik.zeta.sdk.authentication.model.AccessTokenRequest
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull

class ZetaSdkTest {
    @Test
    @Ignore
    fun sdk_halloZetaTest() = runTest {
        val resourceUrl = "" // FQDN
        // Arrange
        val sdk = ZetaSdk.build(
            resourceUrl,
            BuildConfig(
                "demo_client",
                "0.2.0",
                "client-sdk",
                StorageConfig(),
                object : TpmConfig {},
                AuthConfig(
                    listOf(
                        "zero:audience",
                    ),
                    30,
                    true,
                    SmbTokenProvider(
                        SmbTokenProvider.Credentials("", "", ""),
                    ),
                ),
            ),
        )

        // Act
        val client = sdk.httpClient {
            logging(
                LogLevel.ALL,
                object : Logger {
                    override fun log(message: String) {
                        println("log:" + message)
                    }
                },
            )
        }
        ZetaSdk.forget()

        val helloResult = client.get("hellozeta").bodyAsText()
        val helloResult2 = client.get("hellozeta").bodyAsText()

        // Assert
        assertNotNull(helloResult == helloResult2)
    }

    @Test
    @Ignore
    fun token_exchange() = runTest {
        val subjectToken = ""

        val body = AccessTokenRequest(
            clientId = "08663ee5-273e-43c5-8211-75189d2b1047",
            clientAssertion = "client-jwt",
            grantType = "urn:ietf:params:oauth:grant-type:token-exchange",
            requestedTokenType = "urn:ietf:params:oauth:token-type:access_token",
            clientAssertionType = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            subjectToken = subjectToken,
            subjectTokenType = "urn:ietf:params:oauth:token-type:jwt",
            scope = "zero:audience",
        )

        val api = AuthenticationApiImpl(ZetaHttpClientBuilder(""))

        val response = api.requestAccessToken(
            "",
            body,
            "",
        )

        assertNotNull(response.accessToken)
    }
}
