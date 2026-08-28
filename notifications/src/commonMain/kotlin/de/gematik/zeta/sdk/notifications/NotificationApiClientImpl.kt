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

import de.gematik.zeta.sdk.authentication.HttpAuthHeaders
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import de.gematik.zeta.sdk.notifications.model.Channel
import de.gematik.zeta.sdk.notifications.model.ChannelsResponse
import de.gematik.zeta.sdk.notifications.model.GetPushersResponse
import de.gematik.zeta.sdk.notifications.model.NotificationServiceErrorBody
import de.gematik.zeta.sdk.notifications.model.Pusher
import de.gematik.zeta.sdk.notifications.model.RateLimitErrorBody
import de.gematik.zeta.sdk.notifications.model.SetChannelsRequest
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Typed client for the SDK-facing Notification Service API (`GET /pushers`, `POST /pushers/set`,
 * `GET /channels`, `GET`/`POST /channels/{pushkey}`), per
 * `gem-push-notifications-concept/docs_sources/fd_openapi.yaml` (A_29972).
 *
 * Built on the existing `zeta-sdk/network` [ZetaHttpClient] — no custom transport/TLS/retry logic
 * here. Every call authenticates with a DPoP-bound access token (RFC 9449, A_29975–A_29978):
 * the token travels as `Authorization: dpop <token>` and each request carries a fresh proof JWT
 * in the `dpop` header, bound to the request's method/URL and to the token via `ath`. The token
 * comes from [NotificationTokenProvider], the proof from [NotificationDpopProvider]; this client
 * implements no signing, Resource Indicator, or PAR logic of its own.
 *
 * [serviceBaseUrl] is the Notification Service's API base (guard host + PEP prefix, e.g.
 * `.../push/v1`). Each call is issued against the absolute URL derived from it, so the request path
 * keeps the prefix (a leading-slash relative path would let Ktor's base URL drop it) and the
 * proof's `htu` claim matches the URL actually requested.
 *
 * `429 Too Many Requests` responses are retried automatically within the bounds of
 * [rateLimitRetryPolicy] (A_25339/A_27007); every attempt is signed with a fresh DPoP proof.
 * Once the budget is exhausted, the final [NotificationRateLimitedException] reaches the caller.
 */
class NotificationApiClientImpl(
    private val httpClient: ZetaHttpClient,
    private val serviceBaseUrl: String,
    private val tokenProvider: NotificationTokenProvider,
    private val dpopProvider: NotificationDpopProvider,
    private val rateLimitRetryPolicy: RateLimitRetryPolicy = RateLimitRetryPolicy(),
) : NotificationApiClient {
    override suspend fun getPushers(): List<Pusher> =
        authenticatedGet("/pushers", setOf(NotificationScopes.PUSHER_READ)) { it.body<GetPushersResponse>().pushers }

    /** `kind = null` deregisters the pusher identified by `pusher.appId`/`pusher.pushkey`. */
    override suspend fun setPusher(pusher: Pusher) {
        authenticatedPost("/pushers/set", setOf(NotificationScopes.PUSHER_WRITE), pusher) {}
    }

    /** The channels available for the authenticated user and their default status. */
    override suspend fun getChannels(): List<Channel> =
        authenticatedGet("/channels", setOf(NotificationScopes.CHANNEL_READ)) { it.body<ChannelsResponse>().channels }

    /** The channel configuration of one device, identified by [pushkey] (A_29972, device-individual). */
    override suspend fun getChannel(pushkey: String): List<Channel> =
        authenticatedGet("/channels/${pushkey.encodeURLPathPart()}", setOf(NotificationScopes.CHANNEL_READ)) {
            it.body<ChannelsResponse>().channels
        }

    /**
     * Sets [channels] for the device identified by [pushkey]. Channels omitted from [channels]
     * are left unchanged server-side; there is no aggregation or reinterpretation of `not_set` here.
     */
    override suspend fun setChannel(pushkey: String, channels: List<Channel>) {
        authenticatedPost("/channels/${pushkey.encodeURLPathPart()}", setOf(NotificationScopes.CHANNEL_WRITE), SetChannelsRequest(channels)) {}
    }

    private suspend fun <T> authenticatedGet(
        path: String,
        requiredScopes: Set<String>,
        onSuccess: suspend (ZetaHttpResponse) -> T,
    ): T = withUnauthorizedRetry(requiredScopes) {
        withRateLimitRetry {
            val accessToken = tokenProvider.getAccessToken(requiredScopes)
            val url = htu(path)
            val dpopProof = dpopProvider.createDpopProof(HttpMethod.Get.value, url, accessToken)
            val response = httpClient.get(url) {
                header(HttpHeaders.Authorization, "${HttpAuthHeaders.Dpop} $accessToken")
                header(HttpAuthHeaders.Dpop, dpopProof)
            }
            handleResponse(response, onSuccess)
        }
    }

    private suspend inline fun <reified TBody, TResult> authenticatedPost(
        path: String,
        requiredScopes: Set<String>,
        body: TBody,
        noinline onSuccess: suspend (ZetaHttpResponse) -> TResult,
    ): TResult = withUnauthorizedRetry(requiredScopes) {
        withRateLimitRetry {
            val accessToken = tokenProvider.getAccessToken(requiredScopes)
            val url = htu(path)
            val dpopProof = dpopProvider.createDpopProof(HttpMethod.Post.value, url, accessToken)
            val response = httpClient.post(url) {
                header(HttpHeaders.Authorization, "${HttpAuthHeaders.Dpop} $accessToken")
                header(HttpAuthHeaders.Dpop, dpopProof)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            handleResponse(response, onSuccess)
        }
    }

    /**
     * On `401` the cached service token is dropped and the whole operation — token acquisition,
     * proof signing, request — runs exactly once more (A_29975: e.g. token revoked server-side).
     * A second `401` reaches the caller; `403` (insufficient scopes, A_29978) is never retried.
     */
    private suspend fun <T> withUnauthorizedRetry(requiredScopes: Set<String>, attempt: suspend () -> T): T =
        try {
            attempt()
        } catch (_: NotificationApiUnauthorizedException) {
            tokenProvider.invalidate(requiredScopes)
            attempt()
        }

    /**
     * Bounded automatic retry on 429 (A_25339/A_27007): waits `max(retry_after_ms, backoff)`
     * between attempts, doubling the backoff each time. [attempt] runs in full per try, so every
     * retry acquires the token and signs its DPoP proof afresh. Retrying a rate-limited POST is
     * safe — a 429 means the server did not process the request.
     */
    private suspend fun <T> withRateLimitRetry(attempt: suspend () -> T): T {
        var backoffMs = rateLimitRetryPolicy.initialBackoffMs
        repeat(rateLimitRetryPolicy.maxRetries) {
            try {
                return attempt()
            } catch (rateLimited: NotificationRateLimitedException) {
                delay(maxOf(rateLimited.retryAfterMs ?: 0L, backoffMs).milliseconds)
                backoffMs *= 2
            }
        }
        return attempt()
    }

    /**
     * Absolute request URL for [path]: used both as the request target (preserving the base-path
     * prefix) and as the DPoP proof's `htu` claim (RFC 9449: no query/fragment).
     */
    private fun htu(path: String): String = serviceBaseUrl.trimEnd('/') + path

    private suspend fun <T> handleResponse(response: ZetaHttpResponse, onSuccess: suspend (ZetaHttpResponse) -> T): T =
        when (response.status) {
            HttpStatusCode.OK -> onSuccess(response)

            HttpStatusCode.BadRequest -> {
                val error = response.body<NotificationServiceErrorBody>()
                throw InvalidNotificationRequestException(response.raw, error.errorCode, error.errorDetail)
            }

            HttpStatusCode.TooManyRequests -> {
                val error = response.body<RateLimitErrorBody>()
                throw NotificationRateLimitedException(response.raw, error.errorCode, error.errorDetail, error.retryAfterMs)
            }

            HttpStatusCode.Unauthorized -> throw NotificationApiUnauthorizedException(response.raw)

            HttpStatusCode.Forbidden -> throw NotificationApiForbiddenException(response.raw)

            else -> throw UnexpectedNotificationApiException(response.raw)
        }
}
