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

package de.gematik.zeta.sdk.flow

import de.gematik.zeta.logging.Log
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder

/**
 * # zetaPlugin
 *
 * A Ktor `HttpClientPlugin` that routes every outbound request through the
 * **flow orchestrator** so the SDK can evaluate responses, execute needs
 * (via capability handlers), and transparently retry **without rebuilding**
 * the original request.
 *
 * ## How it works
 * - The plugin intercepts at **`HttpSend`**, which runs
 *   after other plugins (JSON, logging, auth, etc.) finished mutating the request.
 * - It hands the **same mutable** [HttpRequestBuilder] to [FlowOrchestrator].
 *   Handlers can mutate this builder (e.g., add headers) and the orchestrator
 *   will resend it as needed—no re-cloning of bodies or URLs is required.
 *
 */
fun zetaPlugin(
    orchestrator: FlowOrchestrator,
    ctx: FlowContext,
) = createClientPlugin("ZetaPlugin") {
    Log.d { "Installing zetaPlugin" }

    client.plugin(HttpSend).intercept { request ->
        if (request.attributes.contains(OrchestratorBypassKey)) {
            Log.d { "Bypass orchestrator for internal calls" }
            return@intercept execute(request)
        }
        return@intercept orchestrator.run(request, ctx)
    }
}
