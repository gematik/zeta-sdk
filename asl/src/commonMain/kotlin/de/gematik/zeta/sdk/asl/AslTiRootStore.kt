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

package de.gematik.zeta.sdk.asl

import de.gematik.zeta.sdk.crypto.X509CertValidator
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock

public class AslTiRootStore(
    private val httpClient: ZetaHttpClient,
    private val environment: TiEnvironment = TiEnvironment.REFERENCE,
) {
    public enum class TiEnvironment(public val url: String) {
        PRODUCTION("https://download.tsl.ti-dienste.de/ECC/ROOT-CA/roots.json"),
        REFERENCE("https://download-ref.tsl.ti-dienste.de/ECC/ROOT-CA/roots.json"),
    }
    private var trustAnchorsDer: List<ByteArray> = emptyList()
    private var lastFetch: Long = 0L
    private val refreshIntervalSeconds = 24 * 3600L

    public suspend fun getTrustAnchors(clock: Clock = Clock.System): List<ByteArray> {
        val now = clock.now().epochSeconds
        if (trustAnchorsDer.isEmpty() || now - lastFetch > refreshIntervalSeconds) {
            refresh(clock)
        }
        return trustAnchorsDer
    }

    private suspend fun refresh(clock: Clock) {
        val response = httpClient.get(environment.url) {
            accept(ContentType.Application.Json)
        }
        trustAnchorsDer = parseRootsJson(response.body<String>())
        lastFetch = clock.now().epochSeconds
    }

    private val rootsJson = Json { ignoreUnknownKeys = true }
    private fun parseRootsJson(json: String): List<ByteArray> =
        rootsJson.decodeFromString<List<RootEntry>>(json)
            .map { Base64.decode(it.cert) }
            .filter { isCurrentlyValid(it) }

    private fun isCurrentlyValid(certDer: ByteArray): Boolean =
        try {
            X509CertValidator().checkValidity(certDer)
            true
        } catch (_: Exception) {
            false
        }
}

@Serializable
private data class RootEntry(
    val cert: String,
    val name: String,
    val nvb: String,
    val nva: String,
)
