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

import de.gematik.zeta.sdk.authentication.smcb.model.ExternalAuthenticateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.ReadCardCertificateResponse
import de.gematik.zeta.sdk.authentication.smcb.model.SignatureObject
import de.gematik.zeta.sdk.authentication.smcb.model.Status
import de.gematik.zeta.sdk.authentication.smcb.model.X509Data
import de.gematik.zeta.sdk.authentication.smcb.model.X509DataInfo
import de.gematik.zeta.sdk.authentication.smcb.model.X509DataInfoList
import de.gematik.zeta.sdk.authentication.smcb.model.X509IssuerSerial
import de.gematik.zeta.sdk.authentication.smcb.model.decodeFromSoap
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectorApiImplTest {

    private val xml = XML {
        indentString = ""
        autoPolymorphic = false
    }

    @Test
    fun `given ReadCardCertificateResponse SOAP when deserialized then returns correct response`() {
        // given
        val soapXml = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                <SOAP-ENV:Header/>
                <SOAP-ENV:Body>
                    <CERT:ReadCardCertificateResponse xmlns:CERT="http://ws.gematik.de/conn/CertificateService/v6.0">
                        <CONN:Status xmlns:CONN="http://ws.gematik.de/conn/ConnectorCommon/v5.0">
                            <CONN:Result>OK</CONN:Result>
                        </CONN:Status>
                        <CERTCMN:X509DataInfoList xmlns:CERTCMN="http://ws.gematik.de/conn/CertificateServiceCommon/v2.0">
                            <CERTCMN:X509DataInfo>
                                <CERTCMN:CertRef>C.AUT</CERTCMN:CertRef>
                                <CERTCMN:X509Data>
                                    <CERTCMN:X509IssuerSerial>
                                        <CERTCMN:X509IssuerName>CN=Test</CERTCMN:X509IssuerName>
                                        <CERTCMN:X509SerialNumber>123</CERTCMN:X509SerialNumber>
                                    </CERTCMN:X509IssuerSerial>
                                    <CERTCMN:X509SubjectName>CN=Subject</CERTCMN:X509SubjectName>
                                    <CERTCMN:X509Certificate>dGVzdENlcnQ=</CERTCMN:X509Certificate>
                                </CERTCMN:X509Data>
                            </CERTCMN:X509DataInfo>
                        </CERTCMN:X509DataInfoList>
                    </CERT:ReadCardCertificateResponse>
                </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()

        // when
        val response = soapXml.decodeFromSoap(ReadCardCertificateResponse.serializer(), xml)

        // then
        assertEquals("OK", response.status.result)
        assertEquals(1, response.x509DataInfoList.x509DataInfo.size)
        assertEquals("dGVzdENlcnQ=", response.x509DataInfoList.x509DataInfo[0].x509Data.x509Certificate)
        assertEquals("CN=Test", response.x509DataInfoList.x509DataInfo[0].x509Data.x509IssuerSerial.x509IssuerName)
        assertEquals("123", response.x509DataInfoList.x509DataInfo[0].x509Data.x509IssuerSerial.x509SerialNumber)
    }

    @Test
    fun `given ExternalAuthenticateResponse SOAP when deserialized then returns correct response`() {
        // given
        val soapXml = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                <SOAP-ENV:Header/>
                <SOAP-ENV:Body>
                    <SIG:ExternalAuthenticateResponse xmlns:SIG="http://ws.gematik.de/conn/SignatureService/v7.4">
                        <CONN:Status xmlns:CONN="http://ws.gematik.de/conn/ConnectorCommon/v5.0">
                            <CONN:Result>OK</CONN:Result>
                        </CONN:Status>
                        <DSS:SignatureObject xmlns:DSS="urn:oasis:names:tc:dss:1.0:core:schema">
                            <DSS:Base64Signature>c2lnbmF0dXJl</DSS:Base64Signature>
                        </DSS:SignatureObject>
                    </SIG:ExternalAuthenticateResponse>
                </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()

        // when
        val response = soapXml.decodeFromSoap(ExternalAuthenticateResponse.serializer(), xml)

        // then
        assertEquals("OK", response.status.result)
        assertEquals("c2lnbmF0dXJl", response.signatureObject.base64Signature)
    }

    @Test
    fun `given SOAP fault when deserialized then throws ConnectorError`() {
        // given
        val soapFault = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                <SOAP-ENV:Header/>
                <SOAP-ENV:Body>
                    <SOAP-ENV:Fault>
                        <faultcode>SOAP-ENV:Server</faultcode>
                        <faultstring>Card not found</faultstring>
                    </SOAP-ENV:Fault>
                </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()

        // when & then
        val error = assertFailsWith<ConnectorError> {
            soapFault.decodeFromSoap(ReadCardCertificateResponse.serializer(), xml)
        }
        assertEquals("SOAP-ENV:Server", error.faultCode)
        assertEquals("Card not found", error.faultString)
    }

    @Test
    fun `given SOAP fault with client code when deserialized then throws ConnectorError with correct code`() {
        // given
        val soapFault = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                <SOAP-ENV:Header/>
                <SOAP-ENV:Body>
                    <SOAP-ENV:Fault>
                        <faultcode>SOAP-ENV:Client</faultcode>
                        <faultstring>Invalid request</faultstring>
                    </SOAP-ENV:Fault>
                </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()

        // when & then
        val error = assertFailsWith<ConnectorError> {
            soapFault.decodeFromSoap(ExternalAuthenticateResponse.serializer(), xml)
        }
        assertEquals("SOAP-ENV:Client", error.faultCode)
        assertEquals("Invalid request", error.faultString)
        assertTrue(error.message!!.contains("SOAP-ENV:Client"))
        assertTrue(error.message!!.contains("Invalid request"))
    }

    // -- ConnectorApi interface contract tests via mock --

    @Test
    fun `given mocked ConnectorApi when readCertificate then returns expected response`() =
        runTest {
            // given
            val mockApi = mockk<ConnectorApi>()
            val expectedResponse = ReadCardCertificateResponse(
                Status("OK"),
                X509DataInfoList(
                    listOf(
                        X509DataInfo(
                            "C.AUT",
                            X509Data(X509IssuerSerial("issuer", "serial"), "subject", "cert123"),
                        ),
                    ),
                ),
            )
            coEvery {
                mockApi.readCertificate("card", "mandant", "client", "workspace", "user")
            } returns expectedResponse

            // when
            val response = mockApi.readCertificate("card", "mandant", "client", "workspace", "user")

            // then
            assertEquals("OK", response.status.result)
            assertEquals("cert123", response.x509DataInfoList.x509DataInfo[0].x509Data.x509Certificate)
        }

    @Test
    fun `given mocked ConnectorApi when externalAuthenticate then returns expected response`() =
        runTest {
            // given
            val mockApi = mockk<ConnectorApi>()
            val expectedResponse = ExternalAuthenticateResponse(
                Status("OK"),
                SignatureObject("base64sig"),
            )
            coEvery {
                mockApi.externalAuthenticate("card", "mandant", "client", "workspace", "user", "challenge")
            } returns expectedResponse

            // when
            val response = mockApi.externalAuthenticate("card", "mandant", "client", "workspace", "user", "challenge")

            // then
            assertEquals("OK", response.status.result)
            assertEquals("base64sig", response.signatureObject.base64Signature)
        }

    @Test
    fun `given mocked ConnectorApi when readCertificate fails then throws ConnectorError`() =
        runTest {
            // given
            val mockApi = mockk<ConnectorApi>()
            coEvery {
                mockApi.readCertificate(any(), any(), any(), any(), any())
            } throws ConnectorError("SOAP-ENV:Server", "Internal", "Internal error")

            // when & then
            val error = assertFailsWith<ConnectorError> {
                mockApi.readCertificate("card", "mandant", "client", "workspace", "user")
            }
            assertEquals("SOAP-ENV:Server", error.faultCode)
        }

    @Test
    fun `given ConnectorApiImpl when constructed then config is accessible`() {
        // given
        val config = SmcbTokenProvider.ConnectorConfig(
            baseUrl = "https://test.connector",
            mandantId = "m1",
            clientSystemId = "cs1",
            workspaceId = "ws1",
            userId = "u1",
            cardHandle = "ch1",
        )

        // when
        val api = ConnectorApiImpl(config)

        // then
        assertEquals("https://test.connector", api.config.baseUrl)
        assertNotNull(api.xml)
    }

    @Test
    fun `given ReadCardCertificateResponse with multiple certificates then all are present`() {
        // given
        val soapXml = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                <SOAP-ENV:Header/>
                <SOAP-ENV:Body>
                    <CERT:ReadCardCertificateResponse xmlns:CERT="http://ws.gematik.de/conn/CertificateService/v6.0">
                        <CONN:Status xmlns:CONN="http://ws.gematik.de/conn/ConnectorCommon/v5.0">
                            <CONN:Result>OK</CONN:Result>
                        </CONN:Status>
                        <CERTCMN:X509DataInfoList xmlns:CERTCMN="http://ws.gematik.de/conn/CertificateServiceCommon/v2.0">
                            <CERTCMN:X509DataInfo>
                                <CERTCMN:CertRef>C.AUT</CERTCMN:CertRef>
                                <CERTCMN:X509Data>
                                    <CERTCMN:X509IssuerSerial>
                                        <CERTCMN:X509IssuerName>CN=Issuer1</CERTCMN:X509IssuerName>
                                        <CERTCMN:X509SerialNumber>1</CERTCMN:X509SerialNumber>
                                    </CERTCMN:X509IssuerSerial>
                                    <CERTCMN:X509SubjectName>CN=Sub1</CERTCMN:X509SubjectName>
                                    <CERTCMN:X509Certificate>Y2VydDE=</CERTCMN:X509Certificate>
                                </CERTCMN:X509Data>
                            </CERTCMN:X509DataInfo>
                            <CERTCMN:X509DataInfo>
                                <CERTCMN:CertRef>C.ENC</CERTCMN:CertRef>
                                <CERTCMN:X509Data>
                                    <CERTCMN:X509IssuerSerial>
                                        <CERTCMN:X509IssuerName>CN=Issuer2</CERTCMN:X509IssuerName>
                                        <CERTCMN:X509SerialNumber>2</CERTCMN:X509SerialNumber>
                                    </CERTCMN:X509IssuerSerial>
                                    <CERTCMN:X509SubjectName>CN=Sub2</CERTCMN:X509SubjectName>
                                    <CERTCMN:X509Certificate>Y2VydDI=</CERTCMN:X509Certificate>
                                </CERTCMN:X509Data>
                            </CERTCMN:X509DataInfo>
                        </CERTCMN:X509DataInfoList>
                    </CERT:ReadCardCertificateResponse>
                </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()

        // when
        val response = soapXml.decodeFromSoap(ReadCardCertificateResponse.serializer(), xml)

        // then
        assertEquals(2, response.x509DataInfoList.x509DataInfo.size)
        assertEquals("Y2VydDE=", response.x509DataInfoList.x509DataInfo[0].x509Data.x509Certificate)
        assertEquals("Y2VydDI=", response.x509DataInfoList.x509DataInfo[1].x509Data.x509Certificate)
    }
}
