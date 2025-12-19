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

package de.gematik.zeta.sdk.attestation.model

import Jwk
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.AndroidProductId
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.AppleProductId
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.Companion.PLATFORM_ANDROID
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.Companion.PLATFORM_APPLE
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.Companion.PLATFORM_LINUX
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.Companion.PLATFORM_WINDOWS
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.LinuxProductId
import de.gematik.zeta.sdk.attestation.model.PlatformProductId.WindowsProductId
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ClientAssertionJwt(
    val header: Header,
    val payload: Payload,
) {
    @Serializable
    data class Header(
        val typ: String,
        val alg: String,
        val jwk: Jwk,
    )

    @Serializable
    data class Payload(
        val iss: String, // client_id
        val sub: String, // client_id
        val aud: List<String>, // token url endpoints
        val exp: Long, // epoch seconds
        val jti: String, // unique id

        @SerialName("client_statement")
        val clientStatement: JsonElement? = null,
        /** The client instance */
        @SerialName("urn:telematik:client-self-assessment") val clientSelfAssessment: ClientSelfAssessment,
    ) {
        init {
            require(iss.isNotEmpty()) { "Payload iss must be not blank" }
            require(sub.isNotEmpty()) { "Payload sub must be not blank" }
            require(aud.isNotEmpty()) { "Payload sub must be not blank" }
            require(aud.all { it.isNotBlank() }) { "Payload aud entries must not be non blank base64 string" }
            require(jti.isNotEmpty()) { "Payload jti must be not blank" }
        }
    }
}

@Serializable
data class ClientSelfAssessment(
    /** The name of the client instance, chosen by the user of the client */
    @SerialName("name") val name: String,
    /** The client identifier */
    @SerialName("client_id") val clientId: String,
    /** The manufacturer identifier */
    @SerialName("manufacturer_id") val manufacturerId: String,
    /** The manufacturer name */
    @SerialName("manufacturer_name") val manufacturerName: String,
    /** The mail address of the owner */
    @SerialName("owner_mail") val ownerMail: String,
    /** The timestamp of the registration in seconds since epoch */
    @SerialName("registration_timestamp") val registrationTimestamp: Long,
    /** Contains platform-specific product identifiers. The exact structure is determined by the 'platform' field */
    @SerialName("platform_product_id") val platformProductId: PlatformProductId,
)

@Serializable(with = PlatformProductIdSerializer::class)
sealed class PlatformProductId {
    companion object {
        const val PLATFORM_APPLE = "apple"
        const val PLATFORM_WINDOWS = "windows"
        const val PLATFORM_LINUX = "linux"
        const val PLATFORM_ANDROID = "android"
    }
    abstract val platform: String

    @Serializable
    @SerialName(PLATFORM_ANDROID)
    data class AndroidProductId(
        override val platform: String = PLATFORM_ANDROID,
        val namespace: String = "android_app",
        @SerialName("package_name") val packageName: String,
        @SerialName("sha256_cert_fingerprints") val sha256CertFingerprints: List<String>,
    ) : PlatformProductId()

    @Serializable
    @SerialName(PLATFORM_APPLE)
    data class AppleProductId(
        override val platform: String = PLATFORM_APPLE,
        @SerialName("platform_type") val platformType: String,
        @SerialName("app_bundle_ids") val appBundleIds: List<String>,
    ) : PlatformProductId()

    @Serializable
    @SerialName(PLATFORM_WINDOWS)
    data class WindowsProductId(
        override val platform: String = PLATFORM_WINDOWS,
        @SerialName("store_id") val storeId: String,
        @SerialName("package_family_name") val packageFamilyName: String,
    ) : PlatformProductId()

    @Serializable
    @SerialName(PLATFORM_LINUX)
    data class LinuxProductId(
        override val platform: String = PLATFORM_LINUX,
        @SerialName("packaging_type") val packagingType: String,
        @SerialName("application_id") val applicationId: String,
        val version: String,
    ) : PlatformProductId()
}

object PlatformProductIdSerializer : JsonContentPolymorphicSerializer<PlatformProductId>(PlatformProductId::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<PlatformProductId> {
        return when (val platform = element.jsonObject["platform"]?.jsonPrimitive?.content) {
            PLATFORM_APPLE -> AppleProductId.serializer()
            PLATFORM_WINDOWS -> WindowsProductId.serializer()
            PLATFORM_LINUX -> LinuxProductId.serializer()
            PLATFORM_ANDROID -> AndroidProductId.serializer()
            else -> throw SerializationException("unknown platform: $platform")
        }
    }
}
