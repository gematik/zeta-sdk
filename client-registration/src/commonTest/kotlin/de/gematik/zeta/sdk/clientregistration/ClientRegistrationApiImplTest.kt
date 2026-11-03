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

package de.gematik.zeta.sdk.clientregistration

import Jwk
import de.gematik.zeta.sdk.clientregistration.model.AndroidAttestation
import de.gematik.zeta.sdk.clientregistration.model.AppleAttestation
import de.gematik.zeta.sdk.clientregistration.model.ClientMetadata
import de.gematik.zeta.sdk.clientregistration.model.ClientRegistrationRequest
import de.gematik.zeta.sdk.clientregistration.model.Jwks
import de.gematik.zeta.sdk.clientregistration.model.SoftwareAttestation
import de.gematik.zeta.sdk.clientregistration.model.ZetaTokenAttestation
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientRegistrationApiImplTest {
    private val endpoint = "https://as.example.com/register"

    private fun json(body: String, status: HttpStatusCode) = MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }

    private fun api(engine: MockEngine) = ClientRegistrationApiImpl(ZetaHttpClientBuilder().contentNegotiation(true).build(engine))

    private val metadata = ClientMetadata(
        tokenEndpointAuthMethod = "private_key_jwt",
        grantTypes = listOf("refresh_token"),
        clientName = "c",
        redirectUris = emptyList(),
        jwks = Jwks(listOf(Jwk("kid", "EC", "ES256", "sig", "P-256", "x", "y"))),
    )

    private val nonce = "AAECAwQFBgcICQoLDA0ODw"

    private fun request() = ClientRegistrationRequest.Software(metadata, SoftwareAttestation(nonce = nonce, signedHashPukClientSig = "sig"))

    private fun sentBody(engine: MockEngine) = (engine.requestHistory.single().body as TextContent).text

    @Test
    fun fetchNonce_returnsTheNonceAsSent() = runTest {
        val engine = MockEngine { respond("$nonce\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())) }

        assertEquals(nonce, api(engine).fetchNonce("https://as.example.com/nonce"))
        assertEquals(HttpMethod.Get, engine.requestHistory.single().method)
    }

    @Test
    fun fetchNonce_throws_onError() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }

        val ex = assertFailsWith<ClientRegistrationException> { api(engine).fetchNonce("https://as.example.com/nonce") }
        assertEquals(HttpStatusCode.ServiceUnavailable, ex.response?.status)
    }

    @Test
    fun register_returnsResponse_on201() = runTest {
        val response = api(json("""{"client_id":"cid"}""", HttpStatusCode.Created)).register(endpoint, request())
        assertEquals("cid", response.clientId)
        assertNull(response.status)
    }

    @Test
    fun register_parsesStatus_on201() = runTest {
        val body = """{"client_id":"cid","status":"pending_user_binding"}"""
        val response = api(json(body, HttpStatusCode.Created)).register(endpoint, request())
        assertEquals("pending_user_binding", response.status)
    }

    @Test
    fun register_ignoresRegistrationAccessToken_on201() = runTest {
        val body = """{"client_id":"cid","registration_access_token":"rat","registration_client_uri":"https://as.example.com/clients/cid"}"""
        val response = api(json(body, HttpStatusCode.Created)).register(endpoint, request())
        assertEquals("cid", response.clientId)
    }

    @Test
    fun register_throws_on202() = runTest {
        val body = """{"transaction_id":"tx-1","status":"pending_verification","challenge_type":"email_otp"}"""
        assertFailsWith<ClientRegistrationException> { api(json(body, HttpStatusCode.Accepted)).register(endpoint, request()) }
    }

    @Test
    fun register_software_sendsNonceAndProofOfPossession() = runTest {
        val engine = json("""{"client_id":"cid"}""", HttpStatusCode.Created)
        api(engine).register(endpoint, request())
        val sent = sentBody(engine)
        assertTrue(sent.contains("\"client_name\":\"c\""))
        assertTrue(sent.contains("\"grant_types\":[\"refresh_token\"]"))
        assertTrue(sent.contains("\"attestation_type\":\"software\""))
        assertTrue(sent.contains("\"nonce\":\"$nonce\""))
        assertTrue(sent.contains("\"signed_hash_puk_client_sig\":\"sig\""))
        assertFalse(sent.contains("redirect_uris"))
        assertFalse(sent.contains("response_types"))
        assertFalse(sent.contains("\"type\""))
        assertFalse(sent.contains("user_email"))
    }

    @Test
    fun register_sendsRedirectUris_whenPresent() = runTest {
        val engine = json("""{"client_id":"cid"}""", HttpStatusCode.Created)
        val mobile = metadata.copy(redirectUris = listOf("https://app.example.com/cb/app/app"))
        api(engine).register(endpoint, ClientRegistrationRequest.Software(mobile, SoftwareAttestation(nonce, "sig")))
        assertTrue(sentBody(engine).contains("\"redirect_uris\":[\"https://app.example.com/cb/app/app\"]"))
    }

    @Test
    fun register_apple_sendsAttestationTypeAndEvidence() = runTest {
        val engine = json("""{"client_id":"cid"}""", HttpStatusCode.Created)
        val request = ClientRegistrationRequest.Apple(metadata, AppleAttestation(appleAttestationObject = "AQI=", signedHashPukClientSig = "sig"))
        api(engine).register(endpoint, request)
        val sent = sentBody(engine)
        assertTrue(sent.contains("\"client_name\":\"c\""))
        assertTrue(sent.contains("\"attestation_type\":\"apple\""))
        assertTrue(sent.contains("\"apple_attestation_object\":\"AQI=\""))
        assertTrue(sent.contains("\"signed_hash_puk_client_sig\":\"sig\""))
        assertFalse(sent.contains("zeta_attestation_token"))
        assertFalse(sent.contains("\"type\""))
    }

    @Test
    fun register_android_sendsAttestationTypeChainAndPop() = runTest {
        val engine = json("""{"client_id":"cid"}""", HttpStatusCode.Created)
        val request = ClientRegistrationRequest.Android(
            metadata,
            AndroidAttestation(keyAttestationCertificateChain = listOf("AQI=", "Aw=="), signedHashPukClientSig = "sig"),
        )
        api(engine).register(endpoint, request)
        val sent = sentBody(engine)
        assertTrue(sent.contains("\"client_name\":\"c\""))
        assertTrue(sent.contains("\"attestation_type\":\"android\""))
        assertTrue(sent.contains("\"android_key_attestation_certificate_chain\":[\"AQI=\",\"Aw==\"]"))
        assertTrue(sent.contains("\"signed_hash_puk_client_sig\":\"sig\""))
        assertFalse(sent.contains("play_integrity_token"))
        assertFalse(sent.contains("apple_attestation_object"))
    }

    @Test
    fun register_android_sendsPlayIntegrityToken_whenPresent() = runTest {
        val engine = json("""{"client_id":"cid"}""", HttpStatusCode.Created)
        val request = ClientRegistrationRequest.Android(
            metadata,
            AndroidAttestation(keyAttestationCertificateChain = listOf("AQI="), signedHashPukClientSig = "sig", playIntegrityToken = "pit"),
        )
        api(engine).register(endpoint, request)
        assertTrue(sentBody(engine).contains("\"play_integrity_token\":\"pit\""))
    }

    @Test
    fun register_zetaToken_sendsAttestationTypeTokenAndPops() = runTest {
        val engine = json("""{"client_id":"cid"}""", HttpStatusCode.Created)
        val request = ClientRegistrationRequest.ZetaAttestationToken(
            metadata,
            ZetaTokenAttestation(zetaAttestationToken = "jwt", signedHashPukClientSig = "sig", attestationPop = "pop"),
        )
        api(engine).register(endpoint, request)
        val sent = sentBody(engine)
        assertTrue(sent.contains("\"attestation_type\":\"zeta_attestation_token\""))
        assertTrue(sent.contains("\"zeta_attestation_token\":\"jwt\""))
        assertTrue(sent.contains("\"signed_hash_puk_client_sig\":\"sig\""))
        assertTrue(sent.contains("\"attestation_pop\":\"pop\""))
        assertFalse(sent.contains("apple_attestation_object"))
        assertFalse(sent.contains("\"type\""))
    }
}
