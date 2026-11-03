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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred

public class OidcRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let {
            OidcCallbackHandler.onCallback(it.toString())
        }

        finish()
    }
}

internal object OidcCallbackHandler {
    private var oidcCallback = CompletableDeferred<String>()
    private var finalCallback = CompletableDeferred<String>()

    suspend fun awaitOidcCallback(): String =
        oidcCallback.await()

    suspend fun awaitFinalCallback(): String =
        finalCallback.await()

    fun onCallback(url: String) {
        val uri = url.toUri()

        when (uri.host) {
            "oidc" -> oidcCallback.complete(url)
            "app" -> finalCallback.complete(url)
        }
    }

    fun clear() {
        oidcCallback = CompletableDeferred()
        finalCallback = CompletableDeferred()
    }
}
