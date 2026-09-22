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

package de.gematik.zeta.sdk.asl.vau

import de.gematik.zeta.sdk.asl.AslApiImplTest
import de.gematik.zeta.sdk.asl.AslHandshakeState
import de.gematik.zeta.sdk.asl.AslHandshakeStateTest
import de.gematik.zeta.sdk.asl.AslStorageImpl
import de.gematik.zeta.sdk.authentication.AccessTokenProvider
import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.crypto.EcdhP256Kem
import de.gematik.zeta.sdk.crypto.Kem
import de.gematik.zeta.sdk.crypto.KemEncapResult
import de.gematik.zeta.sdk.crypto.KeyPair
import de.gematik.zeta.sdk.crypto.ML768Kem
import de.gematik.zeta.sdk.network.http.client.RevocationChecker
import de.gematik.zeta.sdk.network.http.client.RevocationStorage
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import de.gematik.zeta.time.SystemZetaClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class Message1Test {
    @Test
    fun buildMessage1_throwsIllegalArgumentException_sec1Null() {
        // Arrange
        val mlKem = FakeKem(publicKey = ByteArray(32))
        val ecdhKem = FakeKem(publicKey = ByteArray(32), sec1 = null)

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            buildMessage1(mlKem, ecdhKem)
        }
    }

    @Test
    fun buildMessage1_returnsBundle_validKeys() {
        // Arrange
        val mlKemPub = ByteArray(32) { 1 }
        val ecdhSec1 = ByteArray(65) { 0x04.toByte() }
        ecdhSec1[0] = 0x04.toByte()
        val mlKem = FakeKem(publicKey = mlKemPub)
        val ecdhKem = FakeKem(publicKey = ByteArray(32), sec1 = ecdhSec1)

        // Act
        val result = buildMessage1(mlKem, ecdhKem)

        // Assert
        assertNotNull(result.encoded)
        assertNotNull(result.keys)
        assertEquals(mlKemPub, result.keys.ml768Key.skpi)
    }

    @Test
    fun getCid_returnsCid_validHeaders() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
            append("zeta-asl-cid", "/ASL/test-123")
        }.build()

        // Act
        val result = getCid(headers)

        // Assert
        assertEquals("/ASL/test-123", result)
    }

    @Test
    fun getCid_returnsCid_contentTypeWithCharset() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor; charset=utf-8")
            append("zeta-asl-cid", "/ASL/test")
        }.build()

        // Act
        val result = getCid(headers)

        // Assert
        assertEquals("/ASL/test", result)
    }

    @Test
    fun getCid_returnsCid_cidWithHyphens() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
            append("zeta-asl-cid", "/ASL/test-abc-123-xyz")
        }.build()

        // Act
        val result = getCid(headers)

        // Assert
        assertEquals("/ASL/test-abc-123-xyz", result)
    }

    @Test
    fun getCid_returnsCid_cidWithSlashes() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
            append("zeta-asl-cid", "/ASL/path/to/resource")
        }.build()

        // Act
        val result = getCid(headers)

        // Assert
        assertEquals("/ASL/path/to/resource", result)
    }

    @Test
    fun getCid_throwsIllegalArgumentException_missingContentType() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append("zeta-asl-cid", "/ASL/test")
        }.build()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            getCid(headers)
        }
    }

    @Test
    fun getCid_throwsIllegalArgumentException_wrongContentType() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/json")
            append("zeta-asl-cid", "/ASL/test")
        }.build()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            getCid(headers)
        }
    }

    @Test
    fun getCid_throwsIllegalStateException_missingCidHeader() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
        }.build()

        // Act & Assert
        assertFailsWith<IllegalStateException> {
            getCid(headers)
        }
    }

    @Test
    fun getCid_throwsIllegalArgumentException_cidTooLong() {
        // Arrange
        val longCid = "/" + "a".repeat(200)
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
            append("zeta-asl-cid", longCid)
        }.build()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            getCid(headers)
        }
    }

    @Test
    fun getCid_throwsIllegalArgumentException_cidDoesNotStartWithSlash() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
            append("zeta-asl-cid", "ASL/test")
        }.build()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            getCid(headers)
        }
    }

    @Test
    fun getCid_throwsIllegalArgumentException_cidContainsInvalidChars() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
            append("zeta-asl-cid", "/ASL/test@invalid")
        }.build()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            getCid(headers)
        }
    }

    @Test
    fun getCid_throwsIllegalArgumentException_cidContainsSpace() {
        // Arrange
        val headers = HeadersBuilder().apply {
            append(HttpHeaders.ContentType, "application/cbor")
            append("zeta-asl-cid", "/ASL/test with space")
        }.build()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            getCid(headers)
        }
    }

    @Test
    fun sec1ToXY_returnsXY_validSec1() {
        // Arrange
        val sec1 = ByteArray(65)
        sec1[0] = 0x04.toByte()
        for (i in 1..32) sec1[i] = i.toByte()
        for (i in 33..64) sec1[i] = (i + 100).toByte()

        // Act
        val (x, y) = sec1ToXY(sec1)

        // Assert
        assertEquals(32, x.size)
        assertEquals(32, y.size)
        assertContentEquals(sec1.copyOfRange(1, 33), x)
        assertContentEquals(sec1.copyOfRange(33, 65), y)
    }

    @Test
    fun sec1ToXY_throwsIllegalArgumentException_wrongSize() {
        // Arrange
        val sec1 = ByteArray(64)
        sec1[0] = 0x04.toByte()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            sec1ToXY(sec1)
        }
    }

    @Test
    fun sec1ToXY_throwsIllegalArgumentException_wrongFirstByte() {
        // Arrange
        val sec1 = ByteArray(65)
        sec1[0] = 0x03.toByte()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            sec1ToXY(sec1)
        }
    }

    @Test
    fun sec1ToXY_throwsIllegalArgumentException_tooLarge() {
        // Arrange
        val sec1 = ByteArray(66)
        sec1[0] = 0x04.toByte()

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            sec1ToXY(sec1)
        }
    }

    @Test
    fun processMessage1Response_returnsCidResponseAndTranscript() = runTest {
        val messageEncoded = byteArrayOf(1, 2, 3)
        val bodyBytes = byteArrayOf(4, 5, 6)

        val engine = MockEngine {
            respond(
                content = bodyBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Cbor.toString()),
                    "zeta-asl-cid" to listOf("/cid-123"),
                ),
            )
        }

        val client = HttpClient(engine)
        val raw = client.get("https://example.com")
        val response = ZetaHttpResponse(
            status = HttpStatusCode.OK,
            raw = raw,
            headers = emptyMap(),
        )

        val result = processMessage1Response(response, messageEncoded)

        assertEquals("/cid-123", result.cid)
        assertContentEquals(bodyBytes, result.response)
        assertContentEquals(messageEncoded + bodyBytes, result.transcript)
    }

    @Test
    fun processMessage1Response_throws_whenCidHeaderMissing() = runTest {
        val engine = MockEngine {
            respond(
                content = byteArrayOf(4, 5, 6),
                status = HttpStatusCode.OK,
                headers = Headers.Empty,
            )
        }

        val client = HttpClient(engine)
        val raw = client.get("https://example.com")
        val response = ZetaHttpResponse(
            status = HttpStatusCode.OK,
            raw = raw,
            headers = emptyMap(),
        )

        assertFailsWith<IllegalArgumentException> {
            processMessage1Response(response, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun sendMessage1_usesAslUrl_forRequestUrl() = runTest {
        var capturedUrl: String? = null

        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()

            respond(
                content = byteArrayOf(),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Cbor.toString()),
                    "zeta-asl-cid" to listOf("/cid-123"),
                ),
            )
        }

        val httpClient = ZetaHttpClient(HttpClient(engine))

        val request = HttpRequestBuilder().apply {
            url("https://example.com/some/path")
            headers.append(HttpHeaders.Authorization, "${HttpAuthHeaders.Dpop} token")
        }

        val state = buildState(
            request = request,
            httpClient = httpClient,
            accessTokenProvider = AslApiImplTest.FakeAccessTokenProvider(),
        )

        sendMessage1(
            request = request,
            messageEncoded = byteArrayOf(1),
            httpClient = httpClient,
            state = state,
        )

        assertEquals("https://example.com/ASL", capturedUrl)
    }

    private fun buildState(
        request: HttpRequestBuilder,
        httpClient: ZetaHttpClient,
        accessTokenProvider: AccessTokenProvider,
    ): AslHandshakeState {
        val storage =
            RevocationChecker(RevocationStorage(InMemoryStorage(), ResourceScope("", listOf())), ZetaHttpClient(HttpClient {}), clock = SystemZetaClock)

        return AslHandshakeState(
            request = request,
            httpClient = httpClient,
            mlKem = ML768Kem(),
            ecdhKem = EcdhP256Kem(),
            message1 = null,
            message1Result = null,
            m3Result = null,
            m3Encoded = null,
            transcriptHash = null,
            message4 = null,
            accessTokenProvider = accessTokenProvider,
            tpmProvider = AslHandshakeStateTest.FakeTpmProvider(false),
            storage = AslStorageImpl(InMemoryStorage(), ResourceScope("", emptyList())),
            revocationChecker = storage,
            clock = SystemZetaClock,
        )
    }

    private class FakeKem(
        private val publicKey: ByteArray,
        private val sec1: ByteArray? = null,
    ) : Kem {
        override fun generateKeys(): KeyPair {
            return KeyPair(
                skpi = publicKey,
                sec1 = sec1,
                privateKey = byteArrayOf(),
            )
        }

        override fun decapsulate(privateKeyRaw: ByteArray, ciphertext: ByteArray): ByteArray {
            return ByteArray(32)
        }

        override fun encapsulate(peerPublicKey: ByteArray): KemEncapResult {
            return KemEncapResult(byteArrayOf(), byteArrayOf())
        }
    }
}
