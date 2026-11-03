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

package de.gematik.zeta.client.data.service.oidc

import de.gematik.zeta.sdk.authentication.oidc.AuthorizationFailedException
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationLauncher
import de.gematik.zeta.sdk.authentication.oidc.AuthorizationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionCallback
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class WebAuthenticationSessionLauncher(
    private val presentationAnchor: () -> UIWindow = ::currentKeyWindow,
) : AuthorizationLauncher {

    private val mutex = Mutex()

    private var session: ASWebAuthenticationSession? = null
    private var contextProvider: PresentationContextProvider? = null

    override suspend fun open(request: AuthorizationRequest): String =
        mutex.withLock {
            val oidcCallback = launchSession(request.authorizationUrl, request.oidcRedirectUri)
            val params = queryParams(oidcCallback)

            params["error"]?.let { throw AuthorizationFailedException(it, params["error_description"]) }
            val code = params["code"] ?: error("OIDC callback does not contain 'code'")
            val state = params["state"] ?: error("OIDC callback does not contain 'state'")

            val brokerUrl = withQuery(request.brokerEndpoint, "code" to code, "state" to state)
            launchSession(brokerUrl, request.redirectUri)
        }

    private suspend fun launchSession(url: String, callbackUri: String): String =
        withContext(Dispatchers.Main) {
            val startUrl = requireNotNull(NSURL.URLWithString(url)) { "Invalid URL" }
            val scheme = requireNotNull(NSURL.URLWithString(callbackUri)?.scheme) {
                "Callback URI has no scheme: $callbackUri"
            }

            suspendCancellableCoroutine { cont ->
                val newSession = ASWebAuthenticationSession(
                    uRL = startUrl,
                    callback = ASWebAuthenticationSessionCallback.callbackWithCustomScheme(scheme),
                    completionHandler = { resultUrl, error ->
                        clearSession()
                        val finalUrl = resultUrl?.absoluteString
                        when {
                            finalUrl != null -> cont.resume(finalUrl)

                            error != null &&
                                error.domain == ASWebAuthenticationSessionErrorDomain &&
                                error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin ->
                                cont.resumeWithException(
                                    AuthorizationFailedException("access_denied", "User cancelled the login"),
                                )

                            error != null -> cont.resumeWithException(
                                IllegalStateException("Authentication failed: ${error.localizedDescription}"),
                            )

                            else -> cont.resumeWithException(
                                IllegalStateException("Authentication finished without callback URL"),
                            )
                        }
                    },
                )

                val provider = PresentationContextProvider(presentationAnchor)
                newSession.presentationContextProvider = provider
                session = newSession
                contextProvider = provider

                cont.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) {
                        newSession.cancel()
                        clearSession()
                    }
                }

                if (!newSession.start()) {
                    clearSession()
                    cont.resumeWithException(IllegalStateException("Could not start authentication session"))
                }
            }
        }

    private fun clearSession() {
        session = null
        contextProvider = null
    }

    private fun queryParams(url: String): Map<String, String> {
        val components = NSURL.URLWithString(url)
            ?.let { NSURLComponents.componentsWithURL(it, resolvingAgainstBaseURL = false) }
            ?: error("Invalid callback URL")
        return components.queryItems.orEmpty()
            .filterIsInstance<NSURLQueryItem>()
            .mapNotNull { item -> item.value?.let { item.name to it } }
            .toMap()
    }

    private fun withQuery(base: String, vararg params: Pair<String, String>): String {
        val components = requireNotNull(NSURLComponents.componentsWithString(base)) { "Invalid broker endpoint" }
        components.queryItems = components.queryItems.orEmpty() +
            params.map { (name, value) -> NSURLQueryItem.queryItemWithName(name, value) }
        return requireNotNull(components.URL?.absoluteString) { "Could not build broker URL" }
    }
}

private class PresentationContextProvider(
    private val anchor: () -> UIWindow,
) : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(
        session: ASWebAuthenticationSession,
    ): UIWindow = anchor()
}

private fun currentKeyWindow(): UIWindow =
    UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.keyWindow }
        ?: error("No key window available to present the login sheet")
