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

package de.gematik.zeta.sdk.authentication.smcb.model

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("ReadCardCertificateResponse", namespace = CERT_NS, prefix = "CERT")
data class ReadCardCertificateResponse(
    @XmlElement(true)
    @XmlSerialName("Status", namespace = CONN_NS, prefix = "CONN")
    val status: Status,

    @XmlElement(true)
    @XmlSerialName("X509DataInfoList", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509DataInfoList: X509DataInfoList,
)

@Serializable
data class Status(
    @XmlElement(true)
    @XmlSerialName("Result", namespace = CONN_NS, prefix = "CONN")
    val result: String,
)

@Serializable
data class X509DataInfoList(
    @XmlElement(true)
    @XmlSerialName("X509DataInfo", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509DataInfo: List<X509DataInfo>,
)

@Serializable
data class X509DataInfo(
    @XmlElement(true)
    @XmlSerialName("CertRef", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val certRef: String,

    @XmlElement(true)
    @XmlSerialName("X509Data", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509Data: X509Data,
)

@Serializable
data class X509Data(
    @XmlElement(true)
    @XmlSerialName("X509IssuerSerial", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509IssuerSerial: X509IssuerSerial,

    @XmlElement(true)
    @XmlSerialName("X509SubjectName", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509SubjectName: String,

    @XmlElement(true)
    @XmlSerialName("X509Certificate", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509Certificate: String,
)

@Serializable
data class X509IssuerSerial(
    @XmlElement(true)
    @XmlSerialName("X509IssuerName", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509IssuerName: String,

    @XmlElement(true)
    @XmlSerialName("X509SerialNumber", namespace = CERTCMN_NS, prefix = "CERTCMN")
    val x509SerialNumber: String,
)
