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

package de.gematik.zeta.driver.oidc

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.HttpClientBuilderAware
import de.gematik.zeta.sdk.OidcEndpointAware
import de.gematik.zeta.sdk.authentication.oidc.AuthenticationCallback
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.ZetaHttpResponse
import io.ktor.client.plugins.expectSuccess
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter

public class TestDriverAuthenticator(
    private val appBaseUri: String,
    private val testKvnr: String,

) : AuthenticationCallback, HttpClientBuilderAware, OidcEndpointAware {
    override lateinit var httpClientBuilder: ZetaHttpClientBuilder
    override lateinit var resolveAuthorizationEndpoint: suspend () -> String
    override lateinit var resolveBrokerEndpoint: suspend () -> String

    private val oidcRedirectUri = "$appBaseUri/oidc"
    private val appRedirectUri = "$appBaseUri/app"

    override suspend fun authenticationCb(
        clientId: String,
        requestUri: String,
    ): AuthenticationCallback.AuthInfo {
        Log.d { "[Authenticator] START clientId=$clientId requestUri=$requestUri redirectUri=$appBaseUri" }

        val client = httpClientBuilder.build(addExtras = { followRedirects = false })
        val authorizeEndpoint = resolveAuthorizationEndpoint()
        Log.d { "[Authenticator] authorizeEndpoint=$authorizeEndpoint" }

        val initialResponse = client.submitForm(
            urlString = authorizeEndpoint,
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("request_uri", requestUri)
            },
            encodeInQuery = true,
        ) { expectSuccess = false }

        val authUrl = initialResponse.headers[HttpHeaders.Location.lowercase()]
            ?: error("authorization_endpoint did not return a redirect Location header")
        Log.d { "[Authenticator] authUrl (from /auth redirect)=$authUrl" }

        if (authUrl.startsWith(appRedirectUri)) {
            Log.d { "[Authenticator] SSO shortcut, authUrl already matches redirectUri, returning early" }
            return AuthenticationCallback.AuthInfo(finalUrl = authUrl)
        }

        Log.d { "[Authenticator] no SSO shortcut, following full broker flow" }

        val brokerLoginUrl = findBrokerLoginUrl(client, authUrl)
        Log.d { "[Authenticator] brokerLoginUrl=$brokerLoginUrl" }

        val idpAuthUrl = followBrokerToIdp(client, brokerLoginUrl)
        Log.d { "[Authenticator] idpAuthUrl=$idpAuthUrl" }

        val brokerCallbackUrl = authenticateAtIdp(client, idpAuthUrl)
        Log.d { "[Authenticator] brokerCallbackUrl=$brokerCallbackUrl" }

        val finalUrl = followToRedirectUri(client, brokerCallbackUrl)
        Log.d { "[Authenticator] finalUrl (reached redirectUri)=$finalUrl" }

        return AuthenticationCallback.AuthInfo(finalUrl = finalUrl)
    }

    private suspend fun findBrokerLoginUrl(client: ZetaHttpClient, authUrl: String): String {
        Log.d { "[Authenticator] findBrokerLoginUrl: GET $authUrl" }
        val response = client.get(authUrl)

        val statusCode = response.status.value
        Log.d { "[Authenticator] findBrokerLoginUrl: status=$statusCode" }
        if (statusCode in 300..399) {
            val location = response.headers["location"]
                ?: error("Redirect response ($statusCode) without Location header at: $authUrl")
            Log.d { "[Authenticator] findBrokerLoginUrl: redirect Location=$location" }
            return location
        }

        val realmBaseUrl = authUrl.toRealmBaseUrl()
        val loginPageHtml = response.bodyAsText()
        Log.d { "[Authenticator] findBrokerLoginUrl: no redirect, parsing login page HTML (realmBaseUrl=$realmBaseUrl)" }
        return extractHref(loginPageHtml, realmBaseUrl)
            ?.also { Log.d { "[Authenticator] findBrokerLoginUrl: extracted broker href=$it" } }
            ?: error("No identity-provider broker link found on the login page at: $authUrl")
    }

    private suspend fun followBrokerToIdp(client: ZetaHttpClient, brokerLoginUrl: String): String {
        Log.d { "[Authenticator] followBrokerToIdp: GET $brokerLoginUrl" }
        return client.get(brokerLoginUrl) { expectSuccess = false }
            .locationOrError("No redirect from broker link: $brokerLoginUrl")
            .also { Log.d { "[Authenticator] followBrokerToIdp: redirect Location=$it" } }
    }

    private suspend fun authenticateAtIdp(client: ZetaHttpClient, idpAuthUrl: String): String {
        Log.d { "[Authenticator] authenticateAtIdp: GET $idpAuthUrl (real SekIDP KVNR login form)" }
        val response = client.get(idpAuthUrl) { expectSuccess = false }
        val html = response.bodyAsText()

        val formAction = extractFormAction(html, idpAuthUrl.toRealmBaseUrl())
            ?: error("Expected the SekIDP KVNR login form at: $idpAuthUrl, found none")
        val formFields = extractFormFields(html).toMutableMap()

        formFields["user_id"] = testKvnr
        formFields["amr_value"] = AMR_VALUE
        formFields["acr_value"] = ACR_VALUE
        formFields.remove("selected_claims")

        Log.d { "[Authenticator] authenticateAtIdp: submitting KVNR login form action=$formAction fields=${formFields.keys}" }
        return client.submitForm(
            urlString = formAction,
            formParameters = Parameters.build {
                formFields.forEach { (name, value) -> append(name, value) }
            },
            encodeInQuery = true,
        ) { expectSuccess = false }.locationOrError("No redirect after KVNR login form submit at: $formAction")
            .also { Log.d { "[Authenticator] authenticateAtIdp: redirect Location=$it" } }
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
    private suspend fun followToRedirectUri(client: ZetaHttpClient, startUrl: String): String {
        var nextUrl = startUrl
        var attempts = 0
        var reachedInnerCallback = false
        Log.d { "[Authenticator] followToRedirectUri: starting from $startUrl, target prefix=$appRedirectUri" }
        while (!nextUrl.startsWith(appRedirectUri)) {
            check(attempts < MAX_REDIRECT_HOPS) {
                "Did not reach redirectUri after $MAX_REDIRECT_HOPS hops, stuck at: $nextUrl"
            }

            if (!reachedInnerCallback && nextUrl.startsWith(oidcRedirectUri)) {
                reachedInnerCallback = true
                Log.d { "[Authenticator] followToRedirectUri: reached inner callback (oidc) at hop #$attempts, relaying to broker" }

                val url = Url(nextUrl)
                val code = url.parameters["code"] ?: error("No 'code' param at inner callback: $nextUrl")
                val state = url.parameters["state"] ?: error("No 'state' param at inner callback: $nextUrl")
                val brokerEndpoint = resolveBrokerEndpoint()
                nextUrl = "$brokerEndpoint?code=${code.encodeURLParameter()}&state=${state.encodeURLParameter()}"

                Log.d { "[Authenticator] followToRedirectUri: explicit broker relay url=$nextUrl" }
            }

            attempts++
            Log.d { "[Authenticator] followToRedirectUri: hop #$attempts from $nextUrl" }
            nextUrl = advanceOneHop(client, nextUrl)
            Log.d { "[Authenticator] followToRedirectUri: hop #$attempts result=$nextUrl" }
        }
        Log.d { "[Authenticator] followToRedirectUri: reached redirectUri after $attempts hop(s)" }
        return nextUrl
    }

    private suspend fun advanceOneHop(client: ZetaHttpClient, url: String): String {
        val response = client.get(url) { expectSuccess = false }
        response.headers[HttpHeaders.Location.lowercase()]?.let {
            Log.d { "[Authenticator] advanceOneHop: got redirect Location=$it" }
            return it
        }

        Log.d { "[Authenticator] advanceOneHop: no redirect, looking for confirmation form" }
        val html = response.bodyAsText()
        val formAction = extractFormAction(html)
            ?: error("Expected a redirect or a confirmation form at: $url, got neither")
        val formFields = extractFormFields(html)
        Log.d { "[Authenticator] advanceOneHop: found form action=$formAction fields=${formFields.keys}" }

        return client.submitForm(
            urlString = formAction,
            formParameters = Parameters.build {
                formFields.forEach { (name, value) -> append(name, value) }
            },
            encodeInQuery = true,
        ) { expectSuccess = false }.locationOrError("No redirect after submitting form at: $formAction")
            .also { Log.d { "[Authenticator] advanceOneHop: after form submit, redirect Location=$it" } }
    }

    private fun ZetaHttpResponse.locationOrError(message: String): String =
        headers[HttpHeaders.Location.lowercase()] ?: run {
            Log.w { "Authenticator: $message" }
            error(message)
        }

    private fun String.toRealmBaseUrl(): String =
        Url(this).let { "${it.protocol.name}://${it.host}${it.port.takeIf { p -> p != it.protocol.defaultPort }?.let { p -> ":$p" } ?: ""}" }

    private fun extractHref(html: String, baseUrl: String): String? =
        Regex("""href="([^"]*broker/[^"]*)"""")
            .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

    private fun extractFormAction(html: String): String? =
        Regex("""<form[^>]*action="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")

    private companion object {
        const val MAX_REDIRECT_HOPS = 5

        const val ACR_VALUE = "gematik-ehealth-loa-high"
        const val AMR_VALUE = "urn:telematik:auth:eGK"
    }
}
