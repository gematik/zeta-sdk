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

import de.gematik.zeta.sdk.attestation.AttestationApiImpl
import de.gematik.zeta.sdk.attestation.AttestationResponse
import de.gematik.zeta.sdk.attestation.AttestationService
import de.gematik.zeta.sdk.attestation.ErrorCode
import de.gematik.zeta.sdk.attestation.ServiceError
import de.gematik.zeta.sdk.attestation.model.AttestationConfig
import de.gematik.zeta.sdk.attestation.model.ClientStatement
import de.gematik.zeta.sdk.attestation.model.Platform
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.attestation.model.PostureType
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.sdk.tpm.Tpm
import de.gematik.zeta.sdk.tpm.TpmProvider
import de.gematik.zeta.sdk.tpm.TpmStorageImpl
import de.gematik.zeta.time.SystemZetaClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AttestationApiTest {
    val fixedUuid: String = "11111111-2222-3333-4444-555555555555"
    private val fixedEpoch = 1_700_000_000L
    private val fixedExp = fixedEpoch + 300L
    private val fixedNonce = ByteArray(32) { it.toByte() }
    private val fixedThumbprint = ByteArray(32) { (it + 1).toByte() }
    private val fixedKid = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(fixedThumbprint)
    private val defaultClientId = "client-id"
    private val defaultaud = "https://token.example.com"
    private val defaultProductId = "demo-product"
    private val defaultProductVersion = "1.0.0"
    val platformProductIdAppleProductId = PlatformProductId.AppleProductId("apple", "macos", listOf("bundleX"))

    @Test
    fun createClientAssertion_shallReturnJWTWithThreeParts() = runTest {
        // Arrange
        val nonce = "SERVER-NONCE".encodeToByteArray()
        val exp = 1_700_000_000L
        val clientId = "client-sdk"
        val productId = "demo-product"
        val productVersion = "0.2.0"
        val aud = "https://zeta-test.de/token"
        val api = AttestationApiImpl(Tpm.provider(TpmStorageImpl(InMemoryStorage(), ResourceScope("", emptyList()), clock = SystemZetaClock)), { fixedUuid }, clockEpochSeconds = { SystemZetaClock.now().epochSeconds })
        val jwt = api.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = nonce,
            clientId = clientId,
            exp = exp,
            aud = aud,
            platformProductIdAppleProductId,
        )

        val parts = jwt.split('.')
        assertEquals(3, parts.size)
    }

    @Test
    fun createClientAssertion_shallReturnValidHeaders() = runTest {
        // Arrange
        val nonce = "SERVER-NONCE".encodeToByteArray()
        val exp = 1_700_000_000L
        val clientId = "client-sdk"
        val productId = "demo_product"
        val productVersion = "0.2.0"
        val aud = "https://zeta-test.de/token"

        val api = AttestationApiImpl(Tpm.provider(TpmStorageImpl(InMemoryStorage(), ResourceScope("", emptyList()), clock = SystemZetaClock)), { fixedUuid }, clockEpochSeconds = { SystemZetaClock.now().epochSeconds })
        val jwt = api.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = nonce,
            clientId = clientId,
            exp = exp,
            aud = aud,
            platformProductIdAppleProductId,
        )
        val parts = jwt.split('.')
        val header = decodeJson(parts[0])

        assertEquals("JWT", header["typ"]?.jsonPrimitive?.content)
        assertEquals(AsymAlg.ES256.name, header["alg"]?.jsonPrimitive?.content)
        assertTrue(header.contains("jwk"))
    }

    @Test
    fun createClientAssertion_shallReturnValidPayload() = runTest {
        // Arrange
        val nonce = "SERVER-NONCE".encodeToByteArray()
        val exp = 1_700_000_000L
        val clientId = "client-sdk"
        val productId = "demo_product"
        val productVersion = "0.2.0"

        val api = AttestationApiImpl(Tpm.provider(TpmStorageImpl(InMemoryStorage(), ResourceScope("", emptyList()), clock = SystemZetaClock)), { fixedUuid }, clockEpochSeconds = { SystemZetaClock.now().epochSeconds })
        val jwt = api.createClientAssertion(
            productId = productId,
            productVersion = productVersion,
            nonce = nonce,
            clientId = clientId,
            exp = exp,
            aud = "https://zeta-test.de/token",
            platformProductIdAppleProductId,
        )
        val parts = jwt.split('.')
        val payload = decodeJson(parts[1])

        assertEquals(clientId, payload["iss"]?.jsonPrimitive?.content)
        assertEquals(clientId, payload["sub"]?.jsonPrimitive?.content)
        val aud = payload["aud"]?.jsonArray
        assertNotNull(aud)
        assertTrue(aud.any { it.jsonPrimitive.content == "https://zeta-test.de/token" })

        assertEquals(exp, payload["exp"]?.jsonPrimitive?.long)
        assertNotNull(payload["jti"]?.jsonPrimitive)
        assertNotNull(payload["client_statement"]?.jsonObject)
    }

    @Test
    fun clientStatement_matchesSchemaNames() {
        val json = Json

        val statement = ClientStatement(
            sub = "client",
            platform = Platform.ANDROID,
            postureType = PostureType.SOFTWARE,
            posture = JsonObject(emptyMap()),
            attestationTimestamp = 1L,
        )

        val obj = json.encodeToJsonElement(ClientStatement.serializer(), statement).jsonObject

        val expectedKeys = setOf(
            "sub",
            "platform",
            "posture_type",
            "posture",
            "attestation_timestamp",
        )

        assertEquals(expectedKeys, obj.keys)
    }

    private fun platformProductId(): PlatformProductId =
        PlatformProductId.LinuxProductId("linux", "deb", "demo-client", "1.0.0")

    private fun buildImpl(
        tpmProvider: TpmProvider = FakeTpmProvider(),
        attestationConfig: AttestationConfig = AttestationConfig.software(),
        uuidGen: () -> String = { fixedUuid },
        clock: () -> Long = { fixedEpoch },
    ) = AttestationApiImpl(
        tpmProvider = tpmProvider,
        uuidGen = uuidGen,
        clockEpochSeconds = clock,
        attestationConfig = attestationConfig,
    )

    private fun jwtParts(jwt: String): Triple<String, String, String> {
        val p = jwt.split(".")
        assertEquals(3, p.size, "JWT must have exactly 3 parts")
        return Triple(p[0], p[1], p[2])
    }

    private fun decodeJson(b64: String) =
        Json.parseToJsonElement(
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(b64).decodeToString(),
        ).jsonObject

    @Test
    fun createClientAssertion_returnsJwtWithThreeParts() = runTest {
        val jwt = buildImpl().createClientAssertion(
            productId = defaultProductId,
            productVersion = defaultProductVersion,
            nonce = fixedNonce,
            clientId = defaultClientId,
            exp = fixedExp,
            aud = defaultaud,
            platformProductId = platformProductId(),
        )
        assertEquals(3, jwt.split(".").size)
    }

    @Test
    fun createClientAssertion_headerContainsAlgES256() = runTest {
        val (headerB64, _, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertEquals("ES256", decodeJson(headerB64)["alg"]?.jsonPrimitive?.content)
    }

    @Test
    fun createClientAssertion_headerContainsTypJwt() = runTest {
        val (headerB64, _, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertEquals("JWT", decodeJson(headerB64)["typ"]?.jsonPrimitive?.content)
    }

    @Test
    fun createClientAssertion_headerJwkKidMatchesThumbprint() = runTest {
        val (headerB64, _, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            ),
        )
        val kid = decodeJson(headerB64)["jwk"]?.jsonObject?.get("kid")?.jsonPrimitive?.content
        assertEquals(fixedKid, kid)
    }

    @Test
    fun createClientAssertion_payloadIssMatchesClientId() = runTest {
        val (_, payloadB64, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                "my-client", fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertEquals("my-client", decodeJson(payloadB64)["iss"]?.jsonPrimitive?.content)
    }

    @Test
    fun createClientAssertion_payloadSubMatchesClientId() = runTest {
        val (_, payloadB64, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                "my-client", fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertEquals("my-client", decodeJson(payloadB64)["sub"]?.jsonPrimitive?.content)
    }

    @Test
    fun createClientAssertion_payloadAudContainsaud() = runTest {
        val (_, payloadB64, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, "https://my-token.example.com", platformProductId(),
            ),
        )
        val aud = decodeJson(payloadB64)["aud"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(aud?.contains("https://my-token.example.com"), true)
    }

    @Test
    fun createClientAssertion_payloadExpMatchesProvidedValue() = runTest {
        val (_, payloadB64, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertEquals(fixedExp.toString(), decodeJson(payloadB64)["exp"]?.jsonPrimitive?.content)
    }

    @Test
    fun createClientAssertion_payloadContainsClientStatement() = runTest {
        val (_, payloadB64, _) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertTrue(decodeJson(payloadB64).containsKey("client_statement"))
    }

    @Test
    fun createClientAssertion_signsHeaderDotPayload() = runTest {
        val notInScope = "Not yet implemented"
        var capturedSignInput = byteArrayOf()

        val tpm = object : FakeTpmProvider() {
            override suspend fun generateDpopKey(): PublicKeyOut {
                error(notInScope)
            }

            override suspend fun signWithClientKey(input: ByteArray): ByteArray {
                capturedSignInput = input
                return ByteArray(64) { 0x02 }
            }

            override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray {
                error(notInScope)
            }

            override suspend fun forget(resource: String?) {
                error(notInScope)
            }
        }

        val jwt = buildImpl(tpmProvider = tpm).createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )

        val (headerB64, payloadB64, _) = jwtParts(jwt)
        assertContentEquals("$headerB64.$payloadB64".encodeToByteArray(), capturedSignInput)
    }

    @Test
    fun createClientAssertion_signatureIsBase64UrlNoPadding() = runTest {
        val (_, _, sigB64) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertTrue(!sigB64.contains("+") && !sigB64.contains("/") && !sigB64.contains("="))
    }

    @Test
    fun createClientAssertion_signatureMatchesFakeTpmOutput() = runTest {
        val fakeSignature = ByteArray(64) { 0x02 }
        val expected = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(fakeSignature)

        val (_, _, sigB64) = jwtParts(
            buildImpl().createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            ),
        )
        assertEquals(expected, sigB64)
    }

    @Test
    fun createClientAssertion_isDeterministic_withFixedDependencies() = runTest {
        val impl = buildImpl()
        val jwt1 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )
        val jwt2 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )
        assertEquals(jwt1, jwt2)
    }

    @Test
    fun createClientAssertion_changesDifferentJwt_whenClientIdChanges() = runTest {
        val impl = buildImpl()
        val jwt1 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            "client-a", fixedExp, defaultaud, platformProductId(),
        )
        val jwt2 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            "client-b", fixedExp, defaultaud, platformProductId(),
        )
        assertNotEquals(jwt1, jwt2)
    }

    @Test
    fun createClientAssertion_changesDifferentJwt_whenaudChanges() = runTest {
        val impl = buildImpl()
        val jwt1 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, "https://endpoint-a.example.com", platformProductId(),
        )
        val jwt2 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, "https://endpoint-b.example.com", platformProductId(),
        )
        assertNotEquals(jwt1, jwt2)
    }

    @Test
    fun createClientAssertion_changesDifferentJwt_whenExpChanges() = runTest {
        val impl = buildImpl()
        val jwt1 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )
        val jwt2 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp + 999L, defaultaud, platformProductId(),
        )
        assertNotEquals(jwt1, jwt2)
    }

    @Test
    fun createClientAssertion_changesDifferentJwt_whenProductIdChanges() = runTest {
        val impl = buildImpl()
        val jwt1 = impl.createClientAssertion(
            "product-a", defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )
        val jwt2 = impl.createClientAssertion(
            "product-b", defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )
        assertNotEquals(jwt1, jwt2)
    }

    @Test
    fun createClientAssertion_callsUuidGen_onEachInvocation() = runTest {
        var callCount = 0
        val impl = buildImpl(uuidGen = { "jti-${++callCount}" })

        impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )
        impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )

        assertEquals(2, callCount)
    }

    @Test
    fun createClientAssertion_usesClockForTimestamp() = runTest {
        var fakeClock = 1_000_000L
        val impl = buildImpl(clock = { fakeClock })

        val jwt1 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )
        fakeClock = 2_000_000L
        val jwt2 = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )

        assertNotEquals(jwt1, jwt2)
    }

    @Test
    fun createClientAssertion_throwsError_whenTpmConfigButNoAttestationService() = runTest {
        val fakeService = FakeAttestationService(throwError = true)
        val impl = buildImpl(
            attestationConfig = AttestationConfig.tpmCustom(fakeService),
        )

        assertFailsWith<IllegalStateException> {
            impl.createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            )
        }
    }

    @Test
    fun createClientAssertion_returnsJwt_whenTpmConfigWithCustomService() = runTest {
        val fakeService = FakeAttestationService()
        val impl = buildImpl(
            attestationConfig = AttestationConfig.tpmCustom(fakeService),
        )

        val jwt = impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )

        assertEquals(3, jwt.split(".").size)
        assertTrue(fakeService.called)
    }

    @Test
    fun createClientAssertion_throwsError_whenAttestationServiceReturnsError() = runTest {
        val fakeService = FakeAttestationService(returnError = true)
        val impl = buildImpl(
            attestationConfig = AttestationConfig.tpmCustom(fakeService),
        )

        assertFailsWith<IllegalStateException> {
            impl.createClientAssertion(
                defaultProductId, defaultProductVersion, fixedNonce,
                defaultClientId, fixedExp, defaultaud, platformProductId(),
            )
        }
    }

    @Test
    fun createClientAssertion_callsAttestationService_withBase64Challenge() = runTest {
        val fakeService = FakeAttestationService()
        val impl = buildImpl(attestationConfig = AttestationConfig.tpmCustom(fakeService))

        impl.createClientAssertion(
            defaultProductId, defaultProductVersion, fixedNonce,
            defaultClientId, fixedExp, defaultaud, platformProductId(),
        )

        val challenge = fakeService.lastChallenge
        assertTrue(challenge.isNotEmpty())
        Base64.decode(challenge)
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
            jwk = Jwk("fake-kid", "EC", "ES256", "sig", "P-256", "fake-x", "fake-y"),
        )

        override suspend fun signWithClientKey(input: ByteArray): ByteArray = ByteArray(64) { 0x02 }
        override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray = ByteArray(64) { 0x03 }
        override suspend fun readSmbCertificate(p12File: String, alias: String, password: String) = byteArrayOf()
        override suspend fun readSmbCertificateFromBytes(data: ByteArray, alias: String, password: String) = byteArrayOf()
        override suspend fun signWithSmbKey(input: ByteArray, p12File: String, alias: String, password: String) = byteArrayOf()
        override suspend fun signWithSmbKeyFromBytes(input: ByteArray, keystoreBytes: ByteArray, alias: String, password: String): ByteArray = throw NotImplementedError()
        override suspend fun randomUuid(): Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
        override suspend fun getRegistrationNumber(certificate: ByteArray) = "fake-reg"
        override suspend fun forget(resource: String?) {}
    }

    class FakeAttestationService(
        private val throwError: Boolean = false,
        private val returnError: Boolean = false,
    ) : AttestationService {
        var called = false
        var lastChallenge = ""

        override suspend fun generateAttestation(attestationChallenge: String): AttestationResponse {
            if (throwError) error("Service unavailable")
            called = true
            lastChallenge = attestationChallenge
            if (returnError) {
                return AttestationResponse(
                    error = ServiceError(ErrorCode.INTERNAL_ERROR, "Attestation failed"),
                )
            }
            return AttestationResponse(
                tpmQuote = "fake-quote",
                tpmQuoteSignature = "fake-sig",
                attestationKey = "fake-ak",
                eventLog = "fake-log",
                certificateChain = listOf("cert1"),
            )
        }
    }
}
