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

package de.gematik.zeta.sdk.asl

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AslDecryptionPluginTest {
    @Test
    fun decryptResponse_returnsDecryptedContent_whenAslResponseWithExplicitType() = runTest {
        // Arrange
        val fakeAslApi = FakeAslApi()
        val fakeHttpCodec = FakeInnerHttpCodec()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = ByteReadChannel("encrypted-data".encodeToByteArray()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/octet-stream"),
                        ),
                    )
                }
            }
            install(aslDecryptionPlugin(fakeAslApi, fakeHttpCodec))
        }

        // Act
        val response: ByteArray = client.get("/ASL/endpoint").body()

        // Assert
        assertEquals("decrypted content", response.decodeToString())
        assertTrue(fakeAslApi.decryptCalled)
    }

    @Test
    fun decryptResponse_skipsDecryption_whenNonAslResponse() = runTest {
        // Arrange
        val fakeAslApi = FakeAslApi()
        val fakeHttpCodec = FakeInnerHttpCodec()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("plain-json".encodeToByteArray()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                        ),
                    )
                }
            }
            install(aslDecryptionPlugin(fakeAslApi, fakeHttpCodec))
        }

        // Act
        client.get("/normal/endpoint")

        // Assert
        assertFalse(fakeAslApi.decryptCalled)
    }

    @Test
    fun decryptResponse_throwsException_whenDecryptionFailsWithExplicitType() = runTest {
        // Arrange
        val fakeAslApi = object : AslApi {

            override suspend fun encrypt(request: HttpRequestBuilder, passThrough: Boolean?): HttpRequestBuilder {
                return HttpRequestBuilder()
            }

            override suspend fun decrypt(extended: ByteArray): ByteArray {
                throw IllegalArgumentException("Decryption failed")
            }
        }
        val fakeHttpCodec = FakeInnerHttpCodec()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(byteArrayOf(1, 2, 3)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/octet-stream"),
                        ),
                    )
                }
            }
            install(aslDecryptionPlugin(fakeAslApi, fakeHttpCodec))
        }

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            client.get("/ASL/endpoint").bodyAsBytes()
        }
    }

    @Test
    fun decryptResponse_handlesBodyType_whenByteReadChannel() = runTest {
        // Arrange
        val fakeAslApi = FakeAslApi()
        val fakeHttpCodec = FakeInnerHttpCodec()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel("encrypted".encodeToByteArray()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/octet-stream"),
                        ),
                    )
                }
            }
            install(aslDecryptionPlugin(fakeAslApi, fakeHttpCodec))
        }

        // Act
        val response: ByteArray = client.get("/ASL/endpoint").body()

        // Assert
        assertTrue(fakeAslApi.decryptCalled)
        assertEquals("decrypted content", response.decodeToString())
    }
}

class FakeAslApi : AslApi {
    var decryptCalled = false
    override suspend fun encrypt(request: HttpRequestBuilder, passThrough: Boolean?): HttpRequestBuilder {
        return HttpRequestBuilder()
    }

    override suspend fun decrypt(extended: ByteArray): ByteArray {
        decryptCalled = true
        return "decrypted content".encodeToByteArray()
    }
}

class FakeInnerHttpCodec : InnerHttpCodec {
    override fun encodeRequest(builder: HttpRequestBuilder): ByteArray {
        return byteArrayOf()
    }

    override fun decodeResponse(bytes: ByteArray): InnerHttpResponse {
        return InnerHttpResponse(HttpStatusCode.OK.value, "", emptyMap(), bytes)
    }
}
