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

const val ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/"
const val CERT_NS = "http://ws.gematik.de/conn/CertificateService/v6.0"
const val CERTCMN_NS = "http://ws.gematik.de/conn/CertificateServiceCommon/v2.0"
const val CONN_NS = "http://ws.gematik.de/conn/ConnectorCommon/v5.0"
const val CCTX_NS = "http://ws.gematik.de/conn/ConnectorContext/v2.0"

@Serializable
@XmlSerialName("ReadCardCertificate", namespace = CERT_NS, prefix = "CERT")
data class ReadCardCertificate(
    @XmlElement(true)
    @XmlSerialName("CardHandle", namespace = CONN_NS, prefix = "CONN")
    val cardHandle: String,

    @XmlElement(true)
    @XmlSerialName("Context", namespace = CCTX_NS, prefix = "CCTX")
    val context: Context,

    @XmlElement(true)
    @XmlSerialName("CertRefList", namespace = CERT_NS, prefix = "CERT")
    val certRefList: CertRefList,
)

@Serializable
data class Context(
    @XmlElement(true)
    @XmlSerialName("MandantId", namespace = CCTX_NS, prefix = "CCTX")
    val mandantId: String,

    @XmlElement(true)
    @XmlSerialName("ClientSystemId", namespace = CCTX_NS, prefix = "CCTX")
    val clientSystemId: String?,

    @XmlElement(true)
    @XmlSerialName("WorkplaceId", namespace = CCTX_NS, prefix = "CCTX")
    val workplaceId: String?,

    @XmlElement(true)
    @XmlSerialName("MandantId", namespace = CCTX_NS, prefix = "CCTX")
    val userId: String?,
)

@Serializable
data class CertRefList(
    @XmlElement(true)
    @XmlSerialName("CertRef", namespace = CERT_NS, prefix = "CERT")
    val certRefs: List<String>,
)
