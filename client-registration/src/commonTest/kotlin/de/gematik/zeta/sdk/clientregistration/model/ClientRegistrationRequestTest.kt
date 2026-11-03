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

package de.gematik.zeta.sdk.clientregistration.model

import Jwk
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ClientRegistrationRequestTest {
    private val metadata = ClientMetadata(
        tokenEndpointAuthMethod = "private_key_jwt",
        grantTypes = listOf("refresh_token"),
        clientName = "c",
        redirectUris = listOf("https://app/cb"),
        jwks = Jwks(listOf(Jwk("kid", "EC", "ES256", "sig", "P-256", "x", "y"))),
    )

    @Test
    fun clientMetadata_roundTrips() {
        assertEquals(metadata, Json.decodeFromString<ClientMetadata>(Json.encodeToString(metadata)))
    }

    @Test
    fun clientMetadata_omitsEmptyRedirectUris() {
        val encoded = Json.encodeToString(metadata.copy(redirectUris = emptyList()))

        assertFalse(encoded.contains("redirect_uris"))
        assertEquals(emptyList<String>(), Json.decodeFromString<ClientMetadata>(encoded).redirectUris)
    }

    @Test
    fun attestations_roundTrip() {
        val software = SoftwareAttestation(nonce = "n", signedHashPukClientSig = "s")
        val apple = AppleAttestation(appleAttestationObject = "o", signedHashPukClientSig = "s")
        val android = AndroidAttestation(keyAttestationCertificateChain = listOf("a", "b", "c"), signedHashPukClientSig = "s")
        val zetaToken = ZetaTokenAttestation(zetaAttestationToken = "t", signedHashPukClientSig = "s", attestationPop = "p")

        assertEquals(software, Json.decodeFromString<SoftwareAttestation>(Json.encodeToString(software)))
        assertEquals(apple, Json.decodeFromString<AppleAttestation>(Json.encodeToString(apple)))
        assertEquals(android, Json.decodeFromString<AndroidAttestation>(Json.encodeToString(android)))
        assertEquals(zetaToken, Json.decodeFromString<ZetaTokenAttestation>(Json.encodeToString(zetaToken)))
    }

    @Test
    fun software_toJson_isOneFlatObject() {
        val json = ClientRegistrationRequest.Software(metadata, SoftwareAttestation(nonce = "n", signedHashPukClientSig = "s")).toJson()

        assertEquals("c", json["client_name"]?.jsonPrimitive?.content)
        assertEquals("software", json["attestation_type"]?.jsonPrimitive?.content)
        assertEquals("n", json["nonce"]?.jsonPrimitive?.content)
        assertEquals("s", json["signed_hash_puk_client_sig"]?.jsonPrimitive?.content)
    }

    @Test
    fun decode_failsWithoutRequiredFields() {
        assertFailsWith<SerializationException> { Json.decodeFromString<ClientMetadata>("{}") }
        assertFailsWith<SerializationException> { Json.decodeFromString<SoftwareAttestation>("{}") }
        assertFailsWith<SerializationException> { Json.decodeFromString<AppleAttestation>("{}") }
        assertFailsWith<SerializationException> { Json.decodeFromString<AndroidAttestation>("{}") }
        assertFailsWith<SerializationException> { Json.decodeFromString<ZetaTokenAttestation>("{}") }
    }
}
