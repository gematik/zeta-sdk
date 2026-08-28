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

package de.gematik.zeta.sdk.notifications

import io.ktor.client.statement.HttpResponse

/**
 * Typed translation of a Notification Service error response. Carries the original [response]
 * so callers needing raw details (headers, status) are not blocked, without requiring them to
 * reinterpret the Notification Service's status codes/error semantics themselves.
 */
sealed class NotificationApiException(
    val response: HttpResponse,
    message: String,
) : Exception(message)

/** `400` — request payload rejected by the Notification Service (`error.yaml`). */
class InvalidNotificationRequestException(
    response: HttpResponse,
    val errorCode: String,
    val errorDetail: String?,
) : NotificationApiException(response, errorDetail ?: errorCode)

/** `429` — request throttled (`rate_limited.yaml`). */
class NotificationRateLimitedException(
    response: HttpResponse,
    val errorCode: String,
    val errorDetail: String?,
    val retryAfterMs: Long?,
) : NotificationApiException(response, errorDetail ?: errorCode)

/** `401` — the access token from [NotificationTokenProvider] was rejected. */
class NotificationApiUnauthorizedException(response: HttpResponse) :
    NotificationApiException(response, "Notification Service rejected the access token")

/** `403` — the access token was valid but did not authorize this call. */
class NotificationApiForbiddenException(response: HttpResponse) :
    NotificationApiException(response, "Notification Service denied access for this access token")

/** Any other non-2xx status not explicitly modeled for this endpoint. */
class UnexpectedNotificationApiException(response: HttpResponse) :
    NotificationApiException(response, "Unexpected Notification Service response: ${response.status}")
