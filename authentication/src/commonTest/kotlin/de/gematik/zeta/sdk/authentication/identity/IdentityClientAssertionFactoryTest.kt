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

package de.gematik.zeta.sdk.authentication.identity

import Jwk
import PublicKeyOut
import de.gematik.zeta.sdk.tpm.TpmProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class IdentityClientAssertionFactoryTest {
    private val clientId = "mobile-client-id"
    private val issuer = "http://localhost:8080/realms/zeta-guard"
    private val url = "$issuer/zeta/identity/email"
    private val now = 1_730_000_000L
    private val kid = "instance-key-kid"
    private val uuid = Uuid.parse("11111111-2222-3333-4444-555555555555")

    @Test
    fun create_returnsCompactJwsWithThreeParts() = runTest {
        val jwt = factory().create(clientId, issuer, "POST", url)

        assertEquals(3, jwt.split(".").size)
    }

    @Test
    fun create_setsJwtHeaderWithAlgTypAndKid() = runTest {
        val jwt = factory().create(clientId, issuer, "POST", url)
        val header = decodePart(jwt, 0)

        assertEquals("ES256", header["alg"]?.jsonPrimitive?.content)
        assertEquals("JWT", header["typ"]?.jsonPrimitive?.content)
        assertEquals(kid, header["kid"]?.jsonPrimitive?.content)
        assertEquals(setOf("alg", "typ", "kid"), header.keys)
    }

    @Test
    fun create_setsIdentityBindingClaims() = runTest {
        val jwt = factory().create(clientId, issuer, "POST", url)
        val payload = decodePart(jwt, 1)

        assertEquals(clientId, payload["iss"]?.jsonPrimitive?.content)
        assertEquals(clientId, payload["sub"]?.jsonPrimitive?.content)
        assertEquals(issuer, payload["aud"]?.jsonPrimitive?.content)
        assertEquals(uuid.toHexDashString(), payload["jti"]?.jsonPrimitive?.content)
        assertEquals(now.toString(), payload["iat"]?.jsonPrimitive?.content)
        assertEquals((now + IDENTITY_CLIENT_ASSERTION_LIFETIME_SECONDS).toString(), payload["exp"]?.jsonPrimitive?.content)
        assertEquals("POST", payload["htm"]?.jsonPrimitive?.content)
        assertEquals(url, payload["htu"]?.jsonPrimitive?.content)
        assertEquals(setOf("iss", "sub", "aud", "jti", "iat", "exp", "htm", "htu"), payload.keys)
    }

    @Test
    fun create_stripsQueryAndFragmentFromHtu() = runTest {
        val jwt = factory().create(
            clientId,
            issuer,
            "POST",
            "$url?unused=1#fragment",
        )
        val payload = decodePart(jwt, 1)

        assertEquals(url, payload["htu"]?.jsonPrimitive?.content)
    }

    @Test
    fun create_signsHeaderDotPayloadWithInstanceKey() = runTest {
        var signedInput: ByteArray? = null
        val tpm = FakeTpmProvider(kid, uuid) { input ->
            signedInput = input
            ByteArray(64) { 0x02 }
        }

        val jwt = factory(tpm).create(clientId, issuer, "POST", url)
        val parts = jwt.split(".")
        val expectedSignature = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(ByteArray(64) { 0x02 })

        assertContentEquals("${parts[0]}.${parts[1]}".encodeToByteArray(), signedInput)
        assertEquals(expectedSignature, parts[2])
    }

    @Test
    fun create_usesNewJtiOnEachCall() = runTest {
        val uuids = mutableListOf(
            Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        )
        val tpm = FakeTpmProvider(kid, uuid) { ByteArray(64) { 0x02 } }.also { it.uuids = uuids }

        val jti1 = decodePart(factory(tpm).create(clientId, issuer, "POST", url), 1)["jti"]?.jsonPrimitive?.content
        val jti2 = decodePart(factory(tpm).create(clientId, issuer, "POST", url), 1)["jti"]?.jsonPrimitive?.content

        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", jti1)
        assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", jti2)
        assertNotEquals(jti1, jti2)
    }

    @Test
    fun create_doesNotEmbedJwkOrClientStatement() = runTest {
        val jwt = factory().create(clientId, issuer, "POST", url)
        val header = decodePart(jwt, 0)
        val payload = decodePart(jwt, 1)

        assertFalse("jwk" in header)
        assertFalse("client_statement" in payload)
        assertFalse("software_statement" in payload)
    }

    @Test
    fun factory_rejectsLifetimeAboveSixtySeconds() {
        assertFailsWith<IllegalArgumentException> {
            IdentityClientAssertionFactory(FakeTpmProvider(kid, uuid), { now }, 61)
        }
    }

    @Test
    fun identityClientAssertionHeader_equalsHashCodeAndCopy() {
        val header = IdentityClientAssertionHeader(alg = "ES256", typ = "JWT", kid = kid)
        val same = header.copy()

        assertEquals(header, header)
        assertEquals(header, same)
        assertEquals(header.hashCode(), same.hashCode())
        assertEquals("ES256", header.component1())
        assertEquals("JWT", header.component2())
        assertEquals(kid, header.component3())
        assertNotEquals(header, header.copy(alg = "RS256"))
        assertNotEquals(header, header.copy(typ = "at+jwt"))
        assertNotEquals(header, header.copy(kid = "other-kid"))
        assertFalse(header.equals("header"))
        assertNotNull(header.toString())
    }

    @Test
    fun identityClientAssertionClaims_equalsHashCodeAndCopy() {
        val claims = IdentityClientAssertionClaims(
            iss = clientId,
            sub = clientId,
            aud = issuer,
            jti = uuid.toHexDashString(),
            iat = now,
            exp = now + IDENTITY_CLIENT_ASSERTION_LIFETIME_SECONDS,
            htm = "POST",
            htu = url,
        )
        val same = claims.copy()

        assertEquals(claims, claims)
        assertEquals(claims, same)
        assertEquals(claims.hashCode(), same.hashCode())
        assertEquals(clientId, claims.component1())
        assertEquals("POST", claims.component7())
        assertEquals(url, claims.component8())
        assertNotEquals(claims, claims.copy(iss = "other"))
        assertNotEquals(claims, claims.copy(sub = "other"))
        assertNotEquals(claims, claims.copy(aud = "other"))
        assertNotEquals(claims, claims.copy(jti = "other"))
        assertNotEquals(claims, claims.copy(iat = now + 1))
        assertNotEquals(claims, claims.copy(exp = now + 1))
        assertNotEquals(claims, claims.copy(htm = "GET"))
        assertNotEquals(claims, claims.copy(htu = "$url/other"))
        assertFalse(claims.equals("claims"))
        assertNotNull(claims.toString())
    }

    @Test
    fun identityClientAssertionHeader_roundTripsThroughJson() {
        val header = IdentityClientAssertionHeader(alg = "ES256", typ = "JWT", kid = kid)

        val decoded = Json.decodeFromString<IdentityClientAssertionHeader>(Json.encodeToString(header))

        assertEquals(header, decoded)
    }

    @Test
    fun identityClientAssertionClaims_roundTripsThroughJson() {
        val claims = IdentityClientAssertionClaims(
            iss = clientId,
            sub = clientId,
            aud = issuer,
            jti = uuid.toHexDashString(),
            iat = now,
            exp = now + IDENTITY_CLIENT_ASSERTION_LIFETIME_SECONDS,
            htm = "POST",
            htu = url,
        )

        val decoded = Json.decodeFromString<IdentityClientAssertionClaims>(Json.encodeToString(claims))

        assertEquals(claims, decoded)
    }

    private fun factory(tpm: TpmProvider = FakeTpmProvider(kid, uuid)): IdentityClientAssertionFactory =
        IdentityClientAssertionFactory(tpm, clock = { now })

    private fun decodePart(jwt: String, index: Int) = Json.parseToJsonElement(
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(jwt.split(".")[index]).decodeToString(),
    ).jsonObject
}

internal class FakeTpmProvider(
    private val kid: String,
    private val uuid: Uuid,
    var uuids: MutableList<Uuid>? = null,
    private val signer: (ByteArray) -> ByteArray = { ByteArray(64) { 0x02 } },
) : TpmProvider {
    override suspend fun isHardwareBacked(): Boolean = false

    override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut = PublicKeyOut(
        encoded = ByteArray(32) { 0x01 },
        jwk = Jwk(
            kid = kid,
            kty = "EC",
            alg = "ES256",
            use = "sig",
            crv = "P-256",
            x = "fake-x",
            y = "fake-y",
        ),
    )

    override suspend fun generateDpopKey(): PublicKeyOut = error("not in scope of the test")
    override suspend fun signWithClientKey(input: ByteArray): ByteArray = signer(input)
    override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray = error("not in scope of the test")
    override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray = ByteArray(0)
    override suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray = ByteArray(0)
    override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray = ByteArray(0)
    override suspend fun signWithSmbKeyFromBytes(input: ByteArray, keystoreBytes: ByteArray, alias: String, password: String): ByteArray =
        error("not in scope of the test")

    override suspend fun randomUuid(): Uuid = uuids?.removeFirstOrNull() ?: uuid
    override suspend fun getRegistrationNumber(certificate: ByteArray): String = "fake-reg-number"
    override suspend fun forget(resource: String?) {}
}
