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

package de.gematik.zeta.sdk.attestation

import Jwk
import PublicKeyOut
import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.tpm.TpmProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.Base64.PaddingOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

@Suppress("FunctionNaming")
class AttestationApiImplTest {
    private val fixedUuid = "fixed-jti"
    private val fixedEpoch = 1_700_000_000L
    private val fixedExp = fixedEpoch + 300
    private val fixedNonce = ByteArray(32) { it.toByte() }
    private val fixedThumbprint = ByteArray(32) { (it + 1).toByte() }
    private val fixedKid = Base64.UrlSafe.withPadding(PaddingOption.ABSENT).encode(fixedThumbprint)
    private val productId = "product"
    private val productVersion = "1.0.0"
    private val defaultClientId = "client-id"
    private val defaultaud = "https://token.example.com"

    @Test
    fun createClientAssertion_returnsJwtWithThreeParts() = runTest {
        val impl = buildImpl()

        val jwt = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val parts = jwt.split(".")
        assertEquals(3, parts.size)
    }

    @Test
    fun createClientAssertion_setsExpectedHeaderClaims() = runTest {
        val impl = buildImpl()

        val jwt = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val headerJson = Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt.split(".")[0]).decodeToString()
        val header = Json.parseToJsonElement(headerJson).jsonObject

        assertEquals("ES256", header["alg"]?.jsonPrimitive?.content)
        assertEquals("JWT", header["typ"]?.jsonPrimitive?.content)
        assertEquals(fixedKid, header["jwk"]?.jsonObject?.get("kid")?.jsonPrimitive?.content)
    }

    @Test
    fun createClientAssertion_setsExpectedPayloadClaims() = runTest {
        val impl = buildImpl()

        val jwt = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = "my-client",
            exp = fixedExp,
            aud = "https://token.example.com",
            platformProductId = platformProductId(),
        )

        val payloadJson = Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt.split(".")[1]).decodeToString()
        val payload = Json.parseToJsonElement(payloadJson).jsonObject

        val aud = payload["aud"]!!.jsonArray[0].jsonPrimitive.content
        val exp = payload["exp"]!!.jsonPrimitive.content
        val iss = payload["iss"]!!.jsonPrimitive.content
        val sub = payload["sub"]!!.jsonPrimitive.content
        val jti = payload["jti"]!!.jsonPrimitive.content

        assertEquals("my-client", iss)
        assertEquals("my-client", sub)
        assertEquals("https://token.example.com", aud)
        assertEquals(fixedExp.toString(), exp)
        assertEquals(fixedUuid, jti)
    }

    @Test
    fun createClientAssertion_encodesSignatureAsBase64Url() = runTest {
        val impl = buildImpl()

        val jwt = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val signature = jwt.split(".")[2]
        val expectedSignature = Base64.UrlSafe.withPadding(PaddingOption.ABSENT)
            .encode(ByteArray(64) { 0x02 })

        assertEquals(expectedSignature, signature)
    }

    @Test
    fun createClientAssertion_signsHeaderDotPayload() = runTest {
        var signedInput = byteArrayOf()
        val notInScope = "Not scope of test"

        val tpmProvider = object : FakeTpmProvider() {
            override suspend fun generateDpopKey(): PublicKeyOut {
                error(notInScope)
            }

            override suspend fun signWithClientKey(input: ByteArray): ByteArray {
                signedInput = input
                return ByteArray(64) { 0x02 }
            }

            override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray {
                error(notInScope)
            }

            override suspend fun forget(resource: String?) {
                error(notInScope)
            }
        }

        val impl = buildImpl(tpmProvider)

        val jwt = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val parts = jwt.split(".")
        val expectedSignedInput = "${parts[0]}.${parts[1]}".encodeToByteArray()

        assertContentEquals(expectedSignedInput, signedInput)
    }

    @Test
    fun createClientAssertion_isDeterministicWithFixedDependencies() = runTest {
        val impl = buildImpl()

        val jwt1 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val jwt2 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        assertEquals(jwt1, jwt2)
    }

    @Test
    fun createClientAssertion_changesIssAndSubWhenClientIdChanges() = runTest {
        val impl = buildImpl()

        val jwt1 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = "client-a",
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val jwt2 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = "client-b",
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val payload1 = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt1.split(".")[1]).decodeToString(),
        ).jsonObject

        val payload2 = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt2.split(".")[1]).decodeToString(),
        ).jsonObject

        assertNotEquals(payload1["iss"]?.jsonPrimitive?.content, payload2["iss"]?.jsonPrimitive?.content)
        assertNotEquals(payload1["sub"]?.jsonPrimitive?.content, payload2["sub"]?.jsonPrimitive?.content)
    }

    @Test
    fun createClientAssertion_changesAudWhenaudChanges() = runTest {
        val impl = buildImpl()

        val jwt1 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = "https://token-a.example.com",
            platformProductId = platformProductId(),
        )

        val jwt2 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = "https://token-b.example.com",
            platformProductId = platformProductId(),
        )

        val payload1 = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt1.split(".")[1]).decodeToString(),
        ).jsonObject

        val payload2 = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt2.split(".")[1]).decodeToString(),
        ).jsonObject

        val aud1 = payload1["aud"]!!.jsonArray[0].jsonPrimitive.content
        val aud2 = payload2["aud"]!!.jsonArray[0].jsonPrimitive.content

        assertNotEquals(aud1, aud2)
    }

    @Test
    fun createClientAssertion_changesExpWhenExpChanges() = runTest {
        val impl = buildImpl()

        val jwt1 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val jwt2 = impl.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp + 999,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )

        val payload1 = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt1.split(".")[1]).decodeToString(),
        ).jsonObject

        val payload2 = Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(PaddingOption.ABSENT).decode(jwt2.split(".")[1]).decodeToString(),
        ).jsonObject

        assertNotEquals(payload1["exp"]?.jsonPrimitive?.content, payload2["exp"]?.jsonPrimitive?.content)
    }

    open class FakeTpmProvider : TpmProvider {
        override suspend fun isHardwareBacked(): Boolean = false

        override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut = PublicKeyOut(
            encoded = ByteArray(32) { 0x01 },
            jwk = Jwk(
                kid = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA",
                kty = "EC",
                alg = "ES256",
                use = "sig",
                crv = "P-256",
                x = "fake-x",
                y = "fake-y",
            ),
        )

        override suspend fun generateDpopKey(): PublicKeyOut = PublicKeyOut(
            encoded = ByteArray(32) { 0x01 },
            jwk = Jwk(
                kid = "fake-kid",
                kty = "EC",
                alg = "ES256",
                use = "sig",
                crv = "P-256",
                x = "fake-x",
                y = "fake-y",
            ),
        )

        override suspend fun signWithClientKey(input: ByteArray): ByteArray = ByteArray(64) { 0x02 }
        override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray = ByteArray(64) { 0x03 }
        override suspend fun readSmbCertificate(p12File: String, alias: String, password: String): ByteArray = byteArrayOf()
        override suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String): ByteArray = byteArrayOf()
        override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String): ByteArray = byteArrayOf()

        override suspend fun signWithSmbKeyFromBytes(
            input: ByteArray,
            keystoreBytes: ByteArray,
            alias: String,
            password: String,
        ): ByteArray = throw NotImplementedError()

        override suspend fun randomUuid(): Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
        override suspend fun getRegistrationNumber(certificate: ByteArray): String = "fake-reg-number"
        override suspend fun forget(resource: String?) {}
    }

    private fun buildImpl(
        tpmProvider: TpmProvider = FakeTpmProvider(),
        attestationConfig: AttestationConfig = AttestationConfig.software(),
    ) = AttestationApiImpl(
        tpmProvider = tpmProvider,
        uuidGen = { fixedUuid },
        clockEpochSeconds = { fixedEpoch },
        attestationConfig = attestationConfig,
    )

    private fun platformProductId() = when (val p = platform()) {
        is Platform.Jvm.Macos, Platform.Native.Macos ->
            PlatformProductId.AppleProductId("apple", "macos", listOf())

        is Platform.Jvm.Linux ->
            PlatformProductId.LinuxProductId("linux", "", "demo-client", "0.5.0")

        is Platform.Jvm.Windows ->
            PlatformProductId.WindowsProductId("windows", "", "demo-client")

        else -> error("Unknown platform: $p")
    }
}
