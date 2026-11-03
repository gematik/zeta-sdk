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

    private val challenge = ChangeEmailResponse.Pending(
        challengeType = ChallengeType.EMAIL_OTP,
        emailHint = "m***@example.de",
    )

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

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun changeEmailRequest_requiresNewEmailField() {
        assertFailsWith<MissingFieldException> {
            json.decodeFromString<ChangeEmailRequest>("{}")
        }
    }

    @Test
    fun changeEmailResponse_decodesPendingChallenge() {
        val response = json.decodeFromString<ChangeEmailResponse>(
            """{"status":"pending","challenge_type":"email_otp","email_hint":"m***@example.de"}""",
        )

        assertEquals(challenge, response)
    }

    @Test
    fun changeEmailResponse_decodesVerifiedAsEmailStatus() {
        assertEquals(ChangeEmailResponse.Verified, json.decodeFromString<ChangeEmailResponse>("""{"status":"verified"}"""))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun changeEmailResponse_pendingRequiresChallengeTypeAndEmailHint() {
        assertFailsWith<MissingFieldException> {
            json.decodeFromString<ChangeEmailResponse>("""{"status":"pending","challenge_type":"email_otp"}""")
        }
        assertFailsWith<MissingFieldException> {
            json.decodeFromString<ChangeEmailResponse>("""{"status":"pending","email_hint":"m***@example.de"}""")
        }
    }

    @Test
    fun changeEmailResponse_encodesStatusAsDiscriminator() {
        assertEquals(
            """{"status":"pending","challenge_type":"email_otp","email_hint":"m***@example.de"}""",
            json.encodeToString<ChangeEmailResponse>(challenge),
        )
        assertEquals("""{"status":"verified"}""", json.encodeToString<ChangeEmailResponse>(ChangeEmailResponse.Verified))
    }

    @Test
    fun changeEmailResponse_rejectsStatusOutsideSchema() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ChangeEmailResponse>("""{"status":"done"}""")
        }
    }

    @Test
    fun changeEmailResponse_requiresStatus() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ChangeEmailResponse>("""{"challenge_type":"email_otp"}""")
        }
    }

    @Test
    fun problemCode_decodesAllSchemaValues() {
        val codes = listOf("invalidRequest", "unauthorized", "notFound", "internal", "invalidCode", "noPendingChallenge", "unsupportedVerifyType")
            .map { json.decodeFromString<ProblemDetails>("""{"code":"$it"}""").code }

        assertEquals(ProblemCode.entries, codes)
    }

    @Test
    fun problemCode_rejectsCodesRemovedFromSchema() {
        listOf("insufficientScope", "factorRequired", "forbiddenTarget").forEach { code ->
            assertFailsWith<SerializationException> { json.decodeFromString<ProblemDetails>("""{"code":"$code"}""") }
        }
    }

    @Test
    fun problemDetails_equalsHashCodeCopyAndComponents() {
        val problem = ProblemDetails(status = 400, code = ProblemCode.INVALID_REQUEST, title = "Invalid", detail = "bad email")
        val same = problem.copy()

        assertEquals(problem, problem)
        assertEquals(problem, same)
        assertEquals(problem.hashCode(), same.hashCode())
        assertEquals(400, problem.component1())
        assertEquals(ProblemCode.INVALID_REQUEST, problem.component2())
        assertEquals("Invalid", problem.component3())
        assertEquals("bad email", problem.component4())
        assertNotEquals(problem, problem.copy(status = 401))
        assertNotEquals(problem, problem.copy(code = ProblemCode.NOT_FOUND))
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
            problem.copy(status = 400, code = ProblemCode.INVALID_REQUEST, title = "Invalid", detail = "bad email"),
        )
        assertFalse(problem.equals("problem"))
        assertNotNull(problem.toString())
        assertEquals(ProblemDetails().hashCode(), ProblemDetails(status = null, code = null, title = null, detail = null).hashCode())
        assertNotEquals(problem.hashCode(), ProblemDetails().hashCode())
        assertNotEquals(ProblemDetails(status = 400).hashCode(), ProblemDetails().hashCode())
        assertNotEquals(ProblemDetails(code = ProblemCode.UNAUTHORIZED).hashCode(), ProblemDetails().hashCode())
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
        assertEquals(ProblemDetails(), json.decodeFromString<ProblemDetails>("{}"))
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
        val problem = json.decodeFromString<ProblemDetails>("""{"code":"unauthorized"}""")

        assertEquals(ProblemDetails(code = ProblemCode.UNAUTHORIZED), problem)
    }

    @Test
    fun problemDetails_ignoresRfc7807FieldsItDoesNotModel_whenUnknownKeysIgnored() {
        val lenient = Json { ignoreUnknownKeys = true }

        val problem = lenient.decodeFromString<ProblemDetails>(
            """{"type":"about:blank","instance":"/zeta/clients/c1","status":404,"code":"notFound"}""",
        )

        assertEquals(ProblemDetails(status = 404, code = ProblemCode.NOT_FOUND), problem)
    }

    @Test
    fun problemDetails_encodesAllFieldsWhenDefaultsEnabled() {
        val encoded = json.encodeToString(
            ProblemDetails(status = 401, code = ProblemCode.UNAUTHORIZED, title = "Required", detail = "missing"),
        )

        assertEquals(
            """{"status":401,"code":"unauthorized","title":"Required","detail":"missing"}""",
            encoded,
        )
    }

    @Test
    fun problemDetails_encodesNullDefaultsWhenDefaultsEnabled() {
        assertEquals("""{"status":null,"code":null,"title":null,"detail":null}""", json.encodeToString(ProblemDetails()))
    }

    @Test
    fun problemDetails_omitsNullDefaultsWhenDefaultsDisabled() {
        assertEquals("{}", jsonOmitDefaults.encodeToString(ProblemDetails()))
    }

    @Test
    fun problemDetails_encodesOnlyPresentFieldsWhenDefaultsDisabled() {
        assertEquals("""{"code":"notFound"}""", jsonOmitDefaults.encodeToString(ProblemDetails(code = ProblemCode.NOT_FOUND)))
        assertEquals("""{"status":400}""", jsonOmitDefaults.encodeToString(ProblemDetails(status = 400)))
        assertEquals("""{"title":"Invalid"}""", jsonOmitDefaults.encodeToString(ProblemDetails(title = "Invalid")))
        assertEquals("""{"detail":"bad email"}""", jsonOmitDefaults.encodeToString(ProblemDetails(detail = "bad email")))
    }

    // ---------------------------------------------------------------- generated serializers

    @Test
    fun serializers_areUsedExplicitly() {
        val request = ChangeEmailRequest("new@example.de")
        val problem = ProblemDetails(400, ProblemCode.INVALID_REQUEST, "Invalid", "bad email")

        val encodedRequest = json.encodeToString(ChangeEmailRequest.serializer(), request)
        val encodedResponse = json.encodeToString(ChangeEmailResponse.serializer(), challenge as ChangeEmailResponse)
        val encodedProblem = json.encodeToString(ProblemDetails.serializer(), problem)

        assertEquals(request, json.decodeFromString(ChangeEmailRequest.serializer(), encodedRequest))
        assertEquals(challenge, json.decodeFromString(ChangeEmailResponse.serializer(), encodedResponse))
        assertEquals(problem, json.decodeFromString(ProblemDetails.serializer(), encodedProblem))
        assertEquals("/identity/email", CHANGE_EMAIL_PATH)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun serializers_decodeSequentially() {
        val request = ChangeEmailRequest.serializer().deserialize(SequentialFieldsDecoder("new@example.de"))
        val fullResponse = ChangeEmailResponse.Pending.serializer().deserialize(
            SequentialFieldsDecoder("email_otp", "m***@example.de"),
        )
        val filledProblem = ProblemDetails.serializer().deserialize(
            SequentialFieldsDecoder(400, "invalidRequest", "Invalid", "bad email"),
        )
        val emptyProblem = ProblemDetails.serializer().deserialize(SequentialFieldsDecoder(null, null, null, null))

        assertEquals(ChangeEmailRequest("new@example.de"), request)
        assertEquals(challenge, fullResponse)
        assertEquals(ProblemDetails(400, ProblemCode.INVALID_REQUEST, "Invalid", "bad email"), filledProblem)
        assertEquals(ProblemDetails(), emptyProblem)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun serializers_rejectUnknownFieldIndex() {
        assertFailsWith<SerializationException> {
            ChangeEmailRequest.serializer().deserialize(UnknownIndexDecoder())
        }
        assertFailsWith<SerializationException> {
            ChangeEmailResponse.Pending.serializer().deserialize(UnknownIndexDecoder())
        }
        assertFailsWith<SerializationException> {
            ProblemDetails.serializer().deserialize(UnknownIndexDecoder())
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class SequentialFieldsDecoder(private vararg val fields: Any?) : AbstractDecoder() {
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
    override fun decodeLong(): Long = fields[index++] as Long
    override fun decodeString(): String = fields[index++] as String
    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = enumDescriptor.getElementIndex(fields[index++] as String)
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this
}

@OptIn(ExperimentalSerializationApi::class)
private class UnknownIndexDecoder : AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()
    override fun decodeElementIndex(descriptor: SerialDescriptor) = 99
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this
}
