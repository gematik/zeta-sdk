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

package de.gematik.zeta.driver.core.popp

import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.TimeSource

public data class PoppGeneratorConfig(
    val generatorUrl: String,
    val proofMethod: String,
    val patientId: String,
    val insurerId: String,
)

public const val DEFAULT_POPP_PROOF_METHOD: String = "ehc-practitioner-user-x509"
public const val DEFAULT_POPP_PATIENT_ID: String = "X110639491"
public const val DEFAULT_POPP_INSURER_ID: String = "109500969"

public data class PoppActor(val actorId: String, val actorProfessionOid: String)

@Serializable
internal data class TokenParams(
    val proofMethod: String,
    val patientProofTime: String,
    val iat: String,
    val patientId: String,
    val insurerId: String,
    val actorId: String,
    val actorProfessionOid: String,
)

@Serializable
internal data class TokenGeneratorRequest(val tokenParamsList: List<TokenParams>)

@Serializable
internal data class TokenGeneratorResponse(val tokenResults: List<String> = emptyList())

public class PoppTokenProvider(
    private val httpClient: ZetaHttpClient,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = mutableMapOf<Pair<PoppGeneratorConfig, PoppActor>, String?>()
    private val warned = mutableSetOf<String>()

    public suspend fun tokenFor(config: PoppGeneratorConfig, readActor: () -> PoppActor): String? = mutex.withLock {
        if (config.generatorUrl.isBlank()) return@withLock null

        val effective = withDefaults(config)

        val actor = runCatching(readActor).getOrElse {
            warnOnce("[POPP] cannot read SM(C)-B: ${it.message}")
            return@withLock null
        }

        val key = effective to actor
        if (cache.containsKey(key)) {
            Log.d { "[POPP] reusing token for actorId=${actor.actorId}" }
            return@withLock cache[key]
        }

        val token = generate(effective, actor)
        cache[key] = token
        token
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun generate(config: PoppGeneratorConfig, actor: PoppActor): String? {
        val now = nowEpochSeconds().toString()
        val request = TokenGeneratorRequest(
            listOf(
                TokenParams(
                    proofMethod = config.proofMethod,
                    patientProofTime = now,
                    iat = now,
                    patientId = config.patientId,
                    insurerId = config.insurerId,
                    actorId = actor.actorId,
                    actorProfessionOid = actor.actorProfessionOid,
                ),
            ),
        )
        Log.i {
            "[POPP] requesting token from ${config.generatorUrl} proofMethod=${config.proofMethod} " +
                "patientId=${config.patientId} insurerId=${config.insurerId} " +
                "actorId=${actor.actorId} actorProfessionOid=${actor.actorProfessionOid} iat=$now"
        }
        val startedAt = TimeSource.Monotonic.markNow()
        return try {
            val response = httpClient.post(config.generatorUrl) {
                expectSuccess = false
                header(HttpHeaders.Accept, "*/*")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
            Log.i { "[POPP] token generator answered ${response.status.value} in ${startedAt.elapsedNow()}" }
            if (response.status != HttpStatusCode.OK) {
                warnOnce("[POPP] token generator answered ${response.status.value}")
                return null
            }
            val token = json.decodeFromString<TokenGeneratorResponse>(response.body<String>())
                .tokenResults.firstOrNull()
            if (token.isNullOrBlank()) {
                warnOnce("[POPP] token generator returned no token")
                return null
            }
            Log.i { "[POPP] token received actorId=${actor.actorId} actorProfessionOid=${actor.actorProfessionOid} iat=$now" }
            token
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            warnOnce("[POPP] token generator call failed: ${e::class.simpleName}")
            null
        } catch (e: Exception) {
            warnOnce("[POPP] token generator call failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun withDefaults(config: PoppGeneratorConfig): PoppGeneratorConfig = config.copy(
        proofMethod = orDefault("POPP_PROOF_METHOD", config.proofMethod, DEFAULT_POPP_PROOF_METHOD),
        patientId = orDefault("POPP_PATIENT_ID", config.patientId, DEFAULT_POPP_PATIENT_ID),
        insurerId = orDefault("POPP_INSURER_ID", config.insurerId, DEFAULT_POPP_INSURER_ID),
    )

    private fun orDefault(name: String, value: String, default: String): String {
        if (value.isNotBlank()) return value
        warnOnce("[POPP] $name is not set, using default $default")
        return default
    }

    private fun warnOnce(message: String) {
        if (warned.add(message)) Log.w { message }
    }
}

public fun defaultPoppTokenProvider(disableServerValidation: Boolean = false): PoppTokenProvider =
    PoppTokenProvider(
        ZetaHttpClientBuilder()
            .timeouts(10_000, 10_000, 10_000)
            .disableServerValidation(disableServerValidation)
            .build(),
    )

/** Logs which token is sent when no token was generated. */
public fun logStaticPoppTokenFallback(generatorUrl: String, staticToken: String) {
    val source = if (staticToken.isBlank()) "POPP_TOKEN is empty, no PoPP token is sent" else "using static POPP_TOKEN"
    if (generatorUrl.isBlank()) {
        Log.i { "[POPP] POPP_TOKEN_GENERATOR_URL is not set, $source" }
    } else {
        Log.w { "[POPP] no token from the generator, $source" }
    }
}
