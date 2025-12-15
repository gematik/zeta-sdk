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

package de.gematik.zeta.sdk.authentication.smcb

import de.gematik.zeta.sdk.authentication.smcb.model.BinaryString
import de.gematik.zeta.sdk.authentication.smcb.model.CertRefList
import de.gematik.zeta.sdk.authentication.smcb.model.Context
import de.gematik.zeta.sdk.authentication.smcb.model.ExternalAuthenticate
import de.gematik.zeta.sdk.authentication.smcb.model.ExternalAuthenticateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.OptionalInputs
import de.gematik.zeta.sdk.authentication.smcb.model.ReadCardCertificate
import de.gematik.zeta.sdk.authentication.smcb.model.ReadCardCertificateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.decodeFromSoap
import de.gematik.zeta.sdk.authentication.smcb.model.encodeToSoap
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.xml.xml
import nl.adaptivity.xmlutil.serialization.XML

interface ConnectorApi {
    suspend fun readCertificate(
        cardHandle: String,
        mandantId: String,
        clientSystemId: String?,
        workspaceId: String?,
        userId: String?,
    ): ReadCardCertificateResponse

    suspend fun externalAuthenticate(
        cardHandle: String,
        mandantId: String,
        clientSystemId: String?,
        workspaceId: String?,
        userId: String?,
        base64Challenge: String,
    ): ExternalAuthenticateResponse
}

class ConnectorApiImpl(
    val config: SmcbTokenProvider.ConnectorConfig,
) : ConnectorApi {

    val xml = XML {
        indentString = ""
        autoPolymorphic = false
    }

    override suspend fun readCertificate(
        cardHandle: String,
        mandantId: String,
        clientSystemId: String?,
        workspaceId: String?,
        userId: String?,
    ): ReadCardCertificateResponse {
        val client = buildHttpClient(config)
        val request = ReadCardCertificate(
            cardHandle,
            Context(mandantId, clientSystemId, workspaceId, userId),
            CertRefList(listOf("C.AUT")),
        )
        val response = client.post("CertificateService") {
            contentType(ContentType.Text.Xml)
            header("SOAPAction", "ReadCardCertificate")
            setBody(request.encodeToSoap(ReadCardCertificate.serializer(), xml))
        }
        return response.bodyAsText()
            .decodeFromSoap(ReadCardCertificateResponse.serializer(), xml)
    }

    override suspend fun externalAuthenticate(
        cardHandle: String,
        mandantId: String,
        clientSystemId: String?,
        workspaceId: String?,
        userId: String?,
        base64Challenge: String,
    ): ExternalAuthenticateResponse {
        val client = buildHttpClient(config)
        val request = ExternalAuthenticate(
            cardHandle,
            Context(mandantId, clientSystemId, workspaceId, userId),
            OptionalInputs("urn:bsi:tr:03111:ecdsa"),
            BinaryString(base64Challenge),
        )
        val response = client.post("AuthSignatureService") {
            contentType(ContentType.Text.Xml)
            header("SOAPAction", "ExternalAuthenticate")
            setBody(request.encodeToSoap(ExternalAuthenticate.serializer(), xml))
        }
        return response.bodyAsText()
            .decodeFromSoap(ExternalAuthenticateResponse.serializer(), xml)
    }

    private fun buildHttpClient(cfg: SmcbTokenProvider.ConnectorConfig): HttpClient {
        return HttpClient {
            install(DefaultRequest) {
                url {
                    takeFrom(cfg.baseUrl)
                }
            }
            install(Logging) {
                level = LogLevel.ALL
                logger = object : Logger {
                    override fun log(message: String) {
                        println(message)
                    }
                }
            }
            install(ContentNegotiation) {
                xml()
            }
        }
    }
}
