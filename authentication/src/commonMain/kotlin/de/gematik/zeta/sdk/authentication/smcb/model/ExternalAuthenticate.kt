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

const val SIG_NS = "http://ws.gematik.de/conn/SignatureService/v7.4"
const val DSS_NS = "urn:oasis:names:tc:dss:1.0:core:schema"

@Serializable
@XmlSerialName("ExternalAuthenticate", namespace = SIG_NS, prefix = "SIG")
data class ExternalAuthenticate(
    @XmlElement(true)
    @XmlSerialName("CardHandle", namespace = CONN_NS, prefix = "CONN")
    val cardHandle: String,

    @XmlElement(true)
    @XmlSerialName("Context", namespace = CCTX_NS, prefix = "CCTX")
    val context: Context,

    @XmlElement(true)
    @XmlSerialName("OptionalInputs", namespace = SIG_NS, prefix = "SIG")
    val optionalInputs: OptionalInputs? = null,

    @XmlElement(true)
    @XmlSerialName("BinaryString", namespace = SIG_NS, prefix = "SIG")
    val binaryString: BinaryString,
)

@Serializable
data class OptionalInputs(
    @XmlElement(true)
    @XmlSerialName("SignatureType", namespace = DSS_NS, prefix = "dss")
    val signatureType: String? = null,
)

@Serializable
data class BinaryString(
    @XmlElement(true)
    @XmlSerialName("Base64Data", namespace = DSS_NS, prefix = "dss")
    val base64Data: String,
)
