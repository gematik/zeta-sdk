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

package de.gematik.zeta.driver.core.oidc

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationFailedException
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationLauncher
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationRequest
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.plugins.expectSuccess
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.collections.component1
import kotlin.collections.component2

public class TestDriverAuthorizationLauncher(
    private val httpClientBuilder: ZetaHttpClientBuilder,
    private val testKvnr: String,
) : AuthorizationLauncher {

    override suspend fun open(request: AuthorizationRequest): String {
        Log.d { "$TAG START authorizationUrl=${request.authorizationUrl}" }

        val client = httpClientBuilder.build(addExtras = { followRedirects = false })

        val initialResponse = client.get(request.authorizationUrl) { expectSuccess = false }
        val authUrl = initialResponse.locationOrError("Authorization endpoint did not return a redirect")
        Log.d { "$TAG authUrl=$authUrl" }

        if (authUrl.startsWith(request.redirectUri)) {
            Log.d { "$TAG SSO shortcut, returning final redirect" }
            return authUrl
        }

        val brokerCallbackUrl = authenticateAtIdp(client, authUrl)
        Log.d { "$TAG brokerCallbackUrl=$brokerCallbackUrl" }

        val finalUrl = followToRedirectUri(client, brokerCallbackUrl, request)
        Log.d { "$TAG finalUrl=$finalUrl" }
        return finalUrl
    }

    private suspend fun authenticateAtIdp(client: ZetaHttpClient, idpAuthUrl: String): String {
        Log.d { "$TAG authenticateAtIdp: GET $idpAuthUrl (SekIDP KVNR login form)" }
        val response = client.get(idpAuthUrl) { expectSuccess = false }
        val html = response.bodyAsText()

        val formAction = extractFormAction(html, idpAuthUrl.toRealmBaseUrl())
            ?: error("Expected the SekIDP KVNR login form at: $idpAuthUrl, found none")
        val formFields = extractFormFields(html).toMutableMap()

        formFields["user_id"] = testKvnr
        formFields["amr_value"] = AMR_VALUE
        formFields["acr_value"] = ACR_VALUE
        formFields.remove("selected_claims")

        Log.d { "$TAG authenticateAtIdp: submitting KVNR login form action=$formAction fields=${formFields.keys}" }
        return client.submitForm(
            urlString = formAction,
            formParameters = Parameters.build {
                formFields.forEach { (name, value) -> append(name, value) }
            },
            encodeInQuery = true,
        ) { expectSuccess = false }
            .locationOrError("No redirect after KVNR login form submit at: $formAction")
            .also { Log.d { "$TAG authenticateAtIdp: redirect Location=$it" } }
    }

    private suspend fun followToRedirectUri(
        client: ZetaHttpClient,
        startUrl: String,
        request: AuthorizationRequest,
    ): String {
        var nextUrl = startUrl
        var attempts = 0
        var reachedInnerCallback = false
        Log.d { "$TAG followToRedirectUri: starting from $startUrl, target prefix=${request.redirectUri}" }

        while (!nextUrl.startsWith(request.redirectUri)) {
            check(attempts < MAX_REDIRECT_HOPS) {
                "Did not reach redirectUri after $MAX_REDIRECT_HOPS hops, stuck at: $nextUrl"
            }

            if (!reachedInnerCallback && nextUrl.startsWith(request.oidcRedirectUri)) {
                reachedInnerCallback = true
                Log.d { "$TAG followToRedirectUri: reached inner callback (oidc) at hop #$attempts, relaying to broker" }

                val params = Url(nextUrl).parameters
                params["error"]?.let { throw AuthorizationFailedException(it, params["error_description"]) }
                val code = params["code"] ?: error("No 'code' param at inner callback: $nextUrl")
                val state = params["state"] ?: error("No 'state' param at inner callback: $nextUrl")

                nextUrl = URLBuilder(request.brokerEndpoint).apply {
                    parameters.append("code", code)
                    parameters.append("state", state)
                }.buildString()
                Log.d { "$TAG followToRedirectUri: broker relay url=$nextUrl" }
            }

            attempts++
            Log.d { "$TAG followToRedirectUri: hop #$attempts from $nextUrl" }
            nextUrl = advanceOneHop(client, nextUrl)
            Log.d { "$TAG followToRedirectUri: hop #$attempts result=$nextUrl" }
        }

        Log.d { "$TAG followToRedirectUri: reached redirectUri after $attempts hop(s)" }
        return nextUrl
    }

    private suspend fun advanceOneHop(client: ZetaHttpClient, url: String): String {
        val response = client.get(url) { expectSuccess = false }
        response.headers[HttpHeaders.Location.lowercase()]?.let {
            Log.d { "$TAG advanceOneHop: got redirect Location=$it" }
            return it
        }

        Log.d { "$TAG advanceOneHop: no redirect, looking for confirmation form" }
        val html = response.bodyAsText()
        val formAction = extractFormAction(html, url.toRealmBaseUrl())
            ?: error("Expected a redirect or a confirmation form at: $url, got neither")
        val formFields = extractFormFields(html)
        Log.d { "$TAG advanceOneHop: found form action=$formAction fields=${formFields.keys}" }

        return client.submitForm(
            urlString = formAction,
            formParameters = Parameters.build {
                formFields.forEach { (name, value) -> append(name, value) }
            },
            encodeInQuery = true,
        ) { expectSuccess = false }
            .locationOrError("No redirect after submitting form at: $formAction")
            .also { Log.d { "$TAG advanceOneHop: after form submit, redirect Location=$it" } }
    }

    private fun extractFormAction(html: String, baseUrl: String): String? =
        Regex("""<form[^>]*action="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

    private fun extractFormFields(html: String): Map<String, String> {
        val formBlock = Regex("""<form[^>]*>([\s\S]*?)</form>""")
            .find(html)?.groupValues?.get(1) ?: return emptyMap()

        return Regex("""<input[^>]*name="([^"]+)"[^>]*>""")
            .findAll(formBlock)
            .mapNotNull { match ->
                val inputTag = match.value
                val name = match.groupValues[1]
                val value = Regex("""value="([^"]*)"""").find(inputTag)?.groupValues?.get(1) ?: ""
                val type = Regex("""type="([^"]*)"""").find(inputTag)?.groupValues?.get(1)
                val isDisabled = Regex("""\bdisabled\b""").containsMatchIn(inputTag)
                if (type == "submit" || type == "button" || isDisabled) null else name to value.replace("&amp;", "&")
            }
            .toMap()
    }

    private fun ZetaHttpResponse.locationOrError(message: String): String =
        headers[HttpHeaders.Location.lowercase()] ?: run {
            Log.w { "$TAG $message" }
            error(message)
        }

    private fun String.toRealmBaseUrl(): String =
        Url(this).let { url ->
            val port = url.port.takeIf { it != url.protocol.defaultPort }?.let { ":$it" } ?: ""
            "${url.protocol.name}://${url.host}$port"
        }

    private companion object {
        const val TAG = "[TestDriverLauncher]"
        const val MAX_REDIRECT_HOPS = 5
        const val ACR_VALUE = "gematik-ehealth-loa-high"
        const val AMR_VALUE = "urn:telematik:auth:eGK"
    }
}
