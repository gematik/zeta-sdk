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
package de.gematik.zeta.sdk.flow.handler

import Jwk
import de.gematik.zeta.sdk.attestation.DeviceAttestationEvidence
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegistrationRequestBuilderTest {
    private val jwk = Jwk("kid", "EC", "ES256", "sig", "P-256", "x", "y")
    private val uris = listOf("https://app.example.com/cb/app/oidc", "https://app.example.com/cb/app/app")
    private val pop = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
    private val nonce = "AAECAwQFBgcICQoLDA0ODw"
    private val mobileGrantTypes = listOf(
        "authorization_code",
        "urn:ietf:params:oauth:grant-type:token-exchange",
        "refresh_token",
    )
    private val institutionGrantTypes = listOf(
        "urn:ietf:params:oauth:grant-type:token-exchange",
        "refresh_token",
    )

    @Test
    fun software_mobileClient_carriesClientMetadataAndProof() {
        val req = buildSoftwareRegistrationRequest("Client", jwk, nonce, pop, uris)
        assertIs<ClientRegistrationRequest.Software>(req)
        assertEquals("private_key_jwt", req.metadata.tokenEndpointAuthMethod)
        assertEquals(mobileGrantTypes, req.metadata.grantTypes)
        assertEquals("Client", req.metadata.clientName)
        assertEquals(uris, req.metadata.redirectUris)
        assertEquals(listOf(jwk), req.metadata.jwks.keys)
        assertEquals("software", req.attestation.attestationType)
        assertEquals(nonce, req.attestation.nonce)
        assertEquals("-_8", req.attestation.signedHashPukClientSig)
    }

    @Test
    fun software_institutionClient_hasNoRedirectUris_andNoAuthorizationCode() {
        val req = buildSoftwareRegistrationRequest("Client", jwk, nonce, pop, emptyList())
        assertEquals(institutionGrantTypes, req.metadata.grantTypes)
        assertEquals(emptyList<String>(), req.metadata.redirectUris)
    }

    @Test
    fun softwareProofMessage_isRawPointAndDecodedNonce() {
        // RFC 7517 A.1 example key; expected value computed independently with Python: 0x04 || X || Y || nonce bytes.
        val exampleKey = Jwk(
            "kid", "EC", "ES256", "sig", "P-256",
            "MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4",
            "4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM",
        )
        val expected = "0430a0424cd21c2944838a2d75c92b37e76ea20d9f00893a3b4eee8a3c0aafec3e" +
            "e04b65e92456d9888b52b379bdfbd51ee869ef1f0fc65b6659695b6cce081723" +
            "000102030405060708090a0b0c0d0e0f"

        assertContentEquals(expected.hexToByteArray(), softwareProofMessage(exampleKey, nonce))
    }

    @Test
    fun hardwareApple_encodesObjectBase64_andPopBase64Url() {
        val obj = byteArrayOf(0x01, 0x02)
        val req = buildHardwareRegistrationRequest("Client", jwk, DeviceAttestationEvidence.Apple("k", obj), pop, uris)
        assertIs<ClientRegistrationRequest.Apple>(req)
        assertEquals("apple", req.attestation.attestationType)
        assertEquals("AQI=", req.attestation.appleAttestationObject)
        assertEquals("-_8", req.attestation.signedHashPukClientSig)
        assertEquals(mobileGrantTypes, req.metadata.grantTypes)
        assertEquals(uris, req.metadata.redirectUris)
    }

    @Test
    fun hardwareAndroid_encodesChainBase64_andPopBase64Url() {
        val chain = listOf(byteArrayOf(0x01, 0x02), byteArrayOf(0x03))
        val req = buildHardwareRegistrationRequest("Client", jwk, DeviceAttestationEvidence.Android(chain), pop, uris)
        assertIs<ClientRegistrationRequest.Android>(req)
        assertEquals("android", req.attestation.attestationType)
        assertEquals(listOf("AQI=", "Aw=="), req.attestation.keyAttestationCertificateChain)
        assertEquals("-_8", req.attestation.signedHashPukClientSig)
        assertEquals(mobileGrantTypes, req.metadata.grantTypes)
        assertEquals(uris, req.metadata.redirectUris)
    }

    @Test
    fun hardwareAndroid_copiesPlayIntegrityToken() {
        val evidence = DeviceAttestationEvidence.Android(listOf(byteArrayOf(0x01)), playIntegrityToken = "pit")
        val req = buildHardwareRegistrationRequest("Client", jwk, evidence, pop, uris)
        assertIs<ClientRegistrationRequest.Android>(req)
        assertEquals("pit", req.attestation.playIntegrityToken)
    }

    @Test
    fun hardwareAndroid_withoutPlayIntegrityToken_leavesItNull() {
        val req = buildHardwareRegistrationRequest("Client", jwk, DeviceAttestationEvidence.Android(listOf(byteArrayOf(0x01))), pop, uris)
        assertIs<ClientRegistrationRequest.Android>(req)
        assertEquals(null, req.attestation.playIntegrityToken)
    }

    @Test
    fun fastPath_setsTokenAndPops() {
        val attestationPop = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        val req = buildFastPathRegistrationRequest("Client", jwk, "jwt", pop, attestationPop, uris)
        assertEquals("zeta_attestation_token", req.attestation.attestationType)
        assertEquals("jwt", req.attestation.zetaAttestationToken)
        assertEquals("-_8", req.attestation.signedHashPukClientSig)
        assertEquals("+/8=", req.attestation.attestationPop)
        assertEquals(mobileGrantTypes, req.metadata.grantTypes)
    }
}
