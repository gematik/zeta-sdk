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

import de.gematik.zeta.sdk.authentication.smcb.ConnectorError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Envelope", namespace = ENVELOPE_NS, prefix = "SOAP-ENV")
data class SoapEnvelope<T>(
    @XmlElement(true)
    @XmlSerialName("Header", namespace = ENVELOPE_NS, prefix = "SOAP-ENV")
    val header: Header?,

    @XmlElement(true)
    @XmlSerialName("Body", namespace = ENVELOPE_NS, prefix = "SOAP-ENV")
    val body: Body<T>?,
)

@Serializable
object Header

@Serializable
data class Body<T>(
    @XmlElement(true)
    val body: T?,

    @XmlElement(true)
    @XmlSerialName("Fault", namespace = ENVELOPE_NS, prefix = "SOAP-ENV")
    val fault: Fault?,
)

@Serializable
data class Fault(
    @XmlElement(true)
    @XmlSerialName("faultcode", namespace = "", prefix = "")
    val faultCode: String,

    @XmlElement(true)
    @XmlSerialName("faultstring", namespace = "", prefix = "")
    val faultString: String,
)

fun <T> T.toSoapEnvelope() = SoapEnvelope(Header, Body(this, null))

fun <T> SoapEnvelope<T>.fromSoapEnvelope() = this.body?.body ?: error("SoapEnvelope body is empty")

fun <T> T.encodeToSoap(serializer: KSerializer<T>, xml: XML): String {
    return xml.encodeToString(
        serializer = SoapEnvelope.serializer(serializer),
        value = this.toSoapEnvelope(),
    )
}

fun <T> String.decodeFromSoap(serializer: KSerializer<T>, xml: XML): T {
    return xml.decodeFromString(
        deserializer = SoapEnvelope.serializer(serializer),
        string = this,
    ).handleError().fromSoapEnvelope()
}

fun <T> SoapEnvelope<T>.handleError(): SoapEnvelope<T> {
    body?.fault?.let { fault ->
        throw ConnectorError(
            fault.faultCode,
            fault.faultString,
            "${fault.faultCode} - ${fault.faultString}",
        )
    }
    return this
}
