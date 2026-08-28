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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChangeEmailTest {
    private val json = Json { encodeDefaults = true }
    private val jsonOmitDefaults = Json { encodeDefaults = false }

    @Test
    fun changeEmailRequest_equalsHashCodeCopyAndComponents() {
        val request = ChangeEmailRequest(newEmail = "new@example.de")
        val same = request.copy()

        assertEquals(request, request)
        assertEquals(request, same)
        assertEquals(request.hashCode(), same.hashCode())
        assertEquals("new@example.de", request.component1())
        assertNotEquals(request, request.copy(newEmail = "other@example.de"))
        assertFalse(request.equals("request"))
        assertNotNull(request.toString())
    }

    @Test
    fun changeEmailRequest_roundTripsThroughJson() {
        val request = ChangeEmailRequest(newEmail = "new@example.de")

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ChangeEmailRequest>(encoded)

        assertEquals("""{"new_email":"new@example.de"}""", encoded)
        assertEquals(request, decoded)
    }

    @Test
    fun changeEmailResponse_equalsHashCodeCopyAndComponents() {
        val response = ChangeEmailResponse(status = "verified")
        val same = response.copy()

        assertEquals(response, response)
        assertEquals(response, same)
        assertEquals(response.hashCode(), same.hashCode())
        assertEquals("verified", response.component1())
        assertNotEquals(response, response.copy(status = "pending"))
        assertFalse(response.equals("response"))
        assertNotNull(response.toString())
    }

    @Test
    fun changeEmailResponse_roundTripsThroughJson() {
        val response = ChangeEmailResponse(status = "verified")

        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<ChangeEmailResponse>(encoded)

        assertEquals("""{"status":"verified"}""", encoded)
        assertEquals(response, decoded)
    }

    @Test
    fun problemDetails_equalsHashCodeCopyAndComponents() {
        val problem = ProblemDetails(status = 400, code = "invalidRequest", title = "Invalid", detail = "bad email")
        val same = problem.copy()

        assertEquals(problem, problem)
        assertEquals(problem, same)
        assertEquals(problem.hashCode(), same.hashCode())
        assertEquals(400, problem.component1())
        assertEquals("invalidRequest", problem.component2())
        assertEquals("Invalid", problem.component3())
        assertEquals("bad email", problem.component4())
        assertNotEquals(problem, problem.copy(status = 401))
        assertNotEquals(problem, problem.copy(code = "other"))
        assertNotEquals(problem, problem.copy(title = "other"))
        assertNotEquals(problem, problem.copy(detail = "other"))
        assertNotEquals(problem, problem.copy(status = null))
        assertNotEquals(problem, problem.copy(code = null))
        assertNotEquals(problem, problem.copy(title = null))
        assertNotEquals(problem, problem.copy(detail = null))
        assertNotEquals(problem.copy(status = null), problem)
        assertNotEquals(problem.copy(code = null), problem)
        assertNotEquals(problem.copy(title = null), problem)
        assertNotEquals(problem.copy(detail = null), problem)
        assertEquals(
            problem,
            problem.copy(status = 400, code = "invalidRequest", title = "Invalid", detail = "bad email"),
        )
        assertFalse(problem.equals("problem"))
        assertNotNull(problem.toString())
        assertEquals(ProblemDetails().hashCode(), ProblemDetails(status = null, code = null, title = null, detail = null).hashCode())
        assertNotEquals(problem.hashCode(), ProblemDetails().hashCode())
        assertNotEquals(ProblemDetails(status = 400).hashCode(), ProblemDetails().hashCode())
        assertNotEquals(ProblemDetails(code = "x").hashCode(), ProblemDetails().hashCode())
        assertNotEquals(ProblemDetails(title = "x").hashCode(), ProblemDetails().hashCode())
        assertNotEquals(ProblemDetails(detail = "x").hashCode(), ProblemDetails().hashCode())
    }

    @Test
    fun problemDetails_defaultsAreNull() {
        val problem = ProblemDetails()

        assertNull(problem.status)
        assertNull(problem.code)
        assertNull(problem.title)
        assertNull(problem.detail)
        assertEquals(ProblemDetails(), problem)
        assertEquals(ProblemDetails().hashCode(), problem.hashCode())
    }

    @Test
    fun problemDetails_decodesMissingFieldsAsNull() {
        val problem = json.decodeFromString<ProblemDetails>("{}")

        assertEquals(ProblemDetails(), problem)
    }

    @Test
    fun problemDetails_decodesExplicitNulls() {
        val problem = json.decodeFromString<ProblemDetails>(
            """{"status":null,"code":null,"title":null,"detail":null}""",
        )

        assertEquals(ProblemDetails(), problem)
    }

    @Test
    fun problemDetails_decodesPartialFields() {
        val problem = json.decodeFromString<ProblemDetails>("""{"code":"popRequired"}""")

        assertEquals(ProblemDetails(code = "popRequired"), problem)
        assertNull(problem.status)
        assertNull(problem.title)
        assertNull(problem.detail)
    }

    @Test
    fun problemDetails_encodesAllFieldsWhenDefaultsEnabled() {
        val encoded = json.encodeToString(
            ProblemDetails(status = 401, code = "popRequired", title = "Required", detail = "missing"),
        )

        assertEquals(
            """{"status":401,"code":"popRequired","title":"Required","detail":"missing"}""",
            encoded,
        )
    }

    @Test
    fun problemDetails_encodesNullDefaultsWhenDefaultsEnabled() {
        val encoded = json.encodeToString(ProblemDetails())

        assertEquals("""{"status":null,"code":null,"title":null,"detail":null}""", encoded)
    }

    @Test
    fun problemDetails_omitsNullDefaultsWhenDefaultsDisabled() {
        val encoded = jsonOmitDefaults.encodeToString(ProblemDetails())

        assertEquals("{}", encoded)
    }

    @Test
    fun problemDetails_encodesOnlyPresentFieldsWhenDefaultsDisabled() {
        assertEquals("""{"code":"conflict"}""", jsonOmitDefaults.encodeToString(ProblemDetails(code = "conflict")))
        assertEquals("""{"status":400}""", jsonOmitDefaults.encodeToString(ProblemDetails(status = 400)))
        assertEquals("""{"title":"Invalid"}""", jsonOmitDefaults.encodeToString(ProblemDetails(title = "Invalid")))
        assertEquals("""{"detail":"bad email"}""", jsonOmitDefaults.encodeToString(ProblemDetails(detail = "bad email")))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun changeEmailRequest_requiresNewEmailField() {
        assertFailsWith<MissingFieldException> {
            json.decodeFromString<ChangeEmailRequest>("{}")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun changeEmailResponse_requiresStatusField() {
        assertFailsWith<MissingFieldException> {
            json.decodeFromString<ChangeEmailResponse>("{}")
        }
    }

    @Test
    fun serializers_areUsedExplicitly() {
        val request = ChangeEmailRequest("new@example.de")
        val response = ChangeEmailResponse("verified")
        val problem = ProblemDetails(400, "invalidRequest", "Invalid", "bad email")

        val encodedRequest = json.encodeToString(ChangeEmailRequest.serializer(), request)
        val encodedResponse = json.encodeToString(ChangeEmailResponse.serializer(), response)
        val encodedProblem = json.encodeToString(ProblemDetails.serializer(), problem)

        assertEquals(request, json.decodeFromString(ChangeEmailRequest.serializer(), encodedRequest))
        assertEquals(response, json.decodeFromString(ChangeEmailResponse.serializer(), encodedResponse))
        assertEquals(problem, json.decodeFromString(ProblemDetails.serializer(), encodedProblem))
        assertEquals("/zeta/identity/email", CHANGE_EMAIL_PATH)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun serializers_decodeSequentially() {
        val request = ChangeEmailRequest.serializer().deserialize(SequentialStringDecoder("new@example.de"))
        val response = ChangeEmailResponse.serializer().deserialize(SequentialStringDecoder("verified"))
        val filled = ProblemDetails.serializer().deserialize(
            SequentialProblemDecoder(status = 400, code = "invalidRequest", title = "Invalid", detail = "bad email"),
        )
        val empty = ProblemDetails.serializer().deserialize(
            SequentialProblemDecoder(status = null, code = null, title = null, detail = null),
        )

        assertEquals(ChangeEmailRequest("new@example.de"), request)
        assertEquals(ChangeEmailResponse("verified"), response)
        assertEquals(ProblemDetails(400, "invalidRequest", "Invalid", "bad email"), filled)
        assertEquals(ProblemDetails(), empty)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun serializers_rejectUnknownFieldIndex() {
        assertFailsWith<SerializationException> {
            ChangeEmailRequest.serializer().deserialize(UnknownIndexDecoder())
        }
        assertFailsWith<SerializationException> {
            ChangeEmailResponse.serializer().deserialize(UnknownIndexDecoder())
        }
        assertFailsWith<SerializationException> {
            ProblemDetails.serializer().deserialize(UnknownIndexDecoder())
        }
    }

    @Test
    fun changeEmailException_messageUsesDetailWhenPresent() {
        val exception = ChangeEmailException(
            response = null,
            httpStatus = 400,
            code = "invalidRequest",
            title = "Invalid request",
            detail = "new_email is not a valid email",
        )

        assertEquals("new_email is not a valid email", exception.message)
        assertEquals(400, exception.httpStatus)
        assertEquals("invalidRequest", exception.code)
        assertEquals("Invalid request", exception.title)
        assertEquals("new_email is not a valid email", exception.detail)
        assertNull(exception.response)
    }

    @Test
    fun changeEmailException_messageUsesTitleWhenDetailMissing() {
        val exception = ChangeEmailException(
            response = null,
            httpStatus = 401,
            code = "popRequired",
            title = "Client assertion required",
            detail = null,
        )

        assertEquals("Client assertion required", exception.message)
    }

    @Test
    fun changeEmailException_messageUsesCodeWhenDetailAndTitleMissing() {
        val exception = ChangeEmailException(
            response = null,
            httpStatus = 409,
            code = "conflict",
            title = null,
            detail = null,
        )

        assertEquals("conflict", exception.message)
    }

    @Test
    fun changeEmailException_messageUsesHttpFallbackWhenAllMissing() {
        val exception = ChangeEmailException(
            response = null,
            httpStatus = 500,
            code = null,
            title = null,
            detail = null,
        )

        assertEquals("Email change failed with HTTP 500", exception.message)
        assertNull(exception.code)
        assertNull(exception.title)
        assertNull(exception.detail)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class SequentialStringDecoder(private val value: String) : AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()
    override fun decodeSequentially() = true
    override fun decodeElementIndex(descriptor: SerialDescriptor) = CompositeDecoder.DECODE_DONE
    override fun decodeString() = value
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this
}

@OptIn(ExperimentalSerializationApi::class)
private class SequentialProblemDecoder(
    private val status: Int?,
    private val code: String?,
    private val title: String?,
    private val detail: String?,
) : AbstractDecoder() {
    private val fields = listOf(status, code, title, detail)
    private var index = 0

    override val serializersModule = EmptySerializersModule()
    override fun decodeSequentially() = true
    override fun decodeElementIndex(descriptor: SerialDescriptor) = CompositeDecoder.DECODE_DONE
    override fun decodeNotNullMark() = fields[index] != null
    override fun decodeNull(): Nothing? {
        index++
        return null
    }
    override fun decodeInt(): Int = fields[index++] as Int
    override fun decodeString(): String = fields[index++] as String
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this
}

@OptIn(ExperimentalSerializationApi::class)
private class UnknownIndexDecoder : AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()
    override fun decodeElementIndex(descriptor: SerialDescriptor) = 99
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this
}
