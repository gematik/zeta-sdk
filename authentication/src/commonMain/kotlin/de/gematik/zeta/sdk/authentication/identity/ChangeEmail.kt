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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

internal const val CHANGE_EMAIL_PATH = "/identity/email"
internal const val RESEND_EMAIL_CODE_PATH = "$CHANGE_EMAIL_PATH/resend"
internal const val VERIFY_EMAIL_CODE_PATH = "$CHANGE_EMAIL_PATH/verify"

@Serializable
data class ChangeEmailRequest(
    @SerialName("new_email") val newEmail: String,
)

@Serializable
enum class ChallengeType {
    @SerialName("email_otp") EMAIL_OTP,
}

@Serializable
enum class VerifyType {
    @SerialName("email_otp") EMAIL_OTP,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface ChangeEmailResponse {

    @Serializable
    @SerialName("pending")
    data class Pending(
        @SerialName("challenge_type") val challengeType: ChallengeType,
        @SerialName("email_hint") val emailHint: String,
    ) : ChangeEmailResponse

    @Serializable
    @SerialName("verified")
    data object Verified : ChangeEmailResponse
}

/** Schema `ChallengeInfo`. */
@Serializable
data class EmailChallenge(
    @SerialName("challenge_type") val challengeType: ChallengeType,
    @SerialName("email_hint") val emailHint: String? = null,
)

@Serializable
enum class ProblemCode {
    @SerialName("invalidRequest") INVALID_REQUEST,

    @SerialName("unauthorized") UNAUTHORIZED,

    @SerialName("notFound") NOT_FOUND,

    @SerialName("internal") INTERNAL,

    @SerialName("invalidCode") INVALID_CODE,

    @SerialName("noPendingChallenge") NO_PENDING_CHALLENGE,

    @SerialName("unsupportedVerifyType") UNSUPPORTED_VERIFY_TYPE,
}

@Serializable
data class PepError(
    val error: String,
    @SerialName("error_description") val errorDescription: String,
    @SerialName("error_uri") val errorUri: String? = null,
)

/** Schema `Problem`; nullable so a partial error body is kept. */
@Serializable
data class ProblemDetails(
    val status: Int? = null,
    val code: ProblemCode? = null,
    val title: String? = null,
    val detail: String? = null,
)
