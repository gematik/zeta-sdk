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
import PublicKeyOut
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationApiImpl
import de.gematik.zeta.sdk.clientregistration.ClientRegistrationStorageImpl
import de.gematik.zeta.sdk.configuration.ConfigurationStorage
import de.gematik.zeta.sdk.flow.CapabilityResult
import de.gematik.zeta.sdk.flow.FlowContext
import de.gematik.zeta.sdk.flow.FlowNeed
import de.gematik.zeta.sdk.flow.getDummyAuthServerObject
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.tpm.TpmProvider
import derEcdsaToJose
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Checks a software registration's proof of possession with a real P-256 key, the way the deployed guard verifies it. */
class SoftwareProofOfPossessionJvmTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = keyPair.public as ECPublicKey
    private val x = coordinate(publicKey.w.affineX)
    private val y = coordinate(publicKey.w.affineY)
    private val jwk = Jwk("kid", "EC", "ES256", "sig", "P-256", b64Url(x), b64Url(y))
    private val nonce = "AAECAwQFBgcICQoLDA0ODw"
    private val nonceEndpoint = "https://as.example.com/nonce"

    // Signs like every TpmProvider's signWithClientKey: ECDSA over SHA-256 of the input, as JOSE r || s.
    private val tpm = mockk<TpmProvider>().also {
        coEvery { it.getOrGenerateClientInstancePublicKey() } returns PublicKeyOut(byteArrayOf(0x04) + x + y, jwk)
        coEvery { it.signWithClientKey(any()) } answers {
            val der = Signature.getInstance("SHA256withECDSA").apply { initSign(keyPair.private); update(firstArg<ByteArray>()) }.sign()
            derEcdsaToJose(der, 32)
        }
    }

    private val ctx = mockk<FlowContext>().also {
        val configurationStorage = mockk<ConfigurationStorage>()
        coEvery { configurationStorage.getAuthServer() } returns
            getDummyAuthServerObject(registrationEndpoint = "https://as.example.com/register").copy(nonceEndpoint = nonceEndpoint)
        every { it.configurationStorage } returns configurationStorage
        every { it.clientRegistrationStorage } returns ClientRegistrationStorageImpl(InMemoryStorage(), ResourceScope("https://api.example.com", emptyList()))
    }

    @Test
    fun proofOfPossession_passesTheGuardsCheck_andNotTheSchemaForm() = runTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get && request.url.toString() == nonceEndpoint) {
                respond(nonce, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
            } else {
                respond("""{"client_id":"cid"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
        val api = ClientRegistrationApiImpl(ZetaHttpClientBuilder().contentNegotiation(true).build(engine))

        assertIs<CapabilityResult.Done>(ClientRegistrationHandler("client", api, tpm).handle(FlowNeed.ClientRegistration, ctx))

        val body = Json.parseToJsonElement((engine.requestHistory.last().body as TextContent).text).jsonObject
        val sentNonce = body.getValue("nonce").jsonPrimitive.content
        val sentKey = body.getValue("jwks").jsonObject.getValue("keys").jsonArray.single().jsonObject
        val signature = Base64.getUrlDecoder().decode(body.getValue("signed_hash_puk_client_sig").jsonPrimitive.content)
        assertEquals(nonce, sentNonce)

        // The guard's check, read off the request alone (keycloak-zeta PKIUtilKt.toClientHashData + verifySignature):
        // SHA256withECDSA over 0x04 || X || Y || nonce, each of the three base64url-decoded
        val clientHashData = byteArrayOf(0x04) + decode(sentKey.getValue("x")) + decode(sentKey.getValue("y")) + decode(body.getValue("nonce"))
        assertTrue(verifies(clientHashData, signature))

        // dcr-request.yaml 1.2.4's form, h = SHA-256(canonical JWK || UTF-8(nonce)) as the message, must not accept it
        val canonicalJwk = """{"crv":"P-256","kty":"EC","x":"${jwk.x}","y":"${jwk.y}"}"""
        val h = MessageDigest.getInstance("SHA-256").digest((canonicalJwk + sentNonce).toByteArray(Charsets.UTF_8))
        assertFalse(verifies(h, signature))
    }

    private fun decode(value: JsonElement): ByteArray = Base64.getUrlDecoder().decode(value.jsonPrimitive.content)

    private fun verifies(message: ByteArray, derSignature: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(message) }.verify(derSignature)

    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }

    private fun b64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
