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

package de.gematik.zeta.driver

import de.gematik.zeta.driver.model.CreateInstancesRequest
import de.gematik.zeta.driver.model.InstanceState
import de.gematik.zeta.driver.model.SdkInstance
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import de.gematik.zeta.sdk.storage.InMemoryStorage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.lang.System
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

private val loadInstances = ConcurrentHashMap<Int, SdkInstance>()
private val loadInstanceIdGenerator = AtomicInteger(0)
private val instanceConfigFile: File? = System.getenv("CONFIG_FILE")?.let { File(it) }

public fun Application.loadTestDriverRouting() {
    routing {
        post("/load/create_instances") {
            val request = parseCreateInstancesRequest(call)
            val count = request.count?.coerceAtLeast(0) ?: 0
            val autoInit = request.autoInit
            val providedConfigs = request.instances
            val created = mutableListOf<Int>()

            coroutineScope {
                repeat(count) { index ->
                    val id = createInstance(index, providedConfigs, created)
                    if (id != null && autoInit) {
                        launch { initializeInstance(id, loadInstances[id]!!) }
                    }
                }
            }

            call.respondText(
                Json.encodeToString(buildCreateResponse(created, autoInit)),
                ContentType.Application.Json,
            )
        }

        get("/load/list_instances") {
            val list = loadInstances.values.sortedBy { it.id }
            val arr = JsonArray(
                list.map { inst ->
                    buildJsonObject {
                        put("id", JsonPrimitive(inst.id))
                        put("state", JsonPrimitive(inst.state.name))
                        inst.error?.let { put("error", JsonPrimitive(it)) }
                    }
                },
            )
            call.respondText(Json.encodeToString(arr), ContentType.Application.Json)
        }

        delete("/load/delete_instances") {
            val ids = call.parameters.getAll("id")?.mapNotNull { it.toIntOrNull() }
            val removed = if (ids.isNullOrEmpty()) {
                val keys = loadInstances.keys.toList()
                loadInstances.clear()
                loadInstanceIdGenerator.set(0)
                keys
            } else {
                ids.filter { loadInstances.remove(it) != null }
            }
            call.respondText("""{"removed": ${removed.size}, "ids": $removed}""", ContentType.Application.Json)
        }

        route("/load/{instanceIndex}/{path...}") {
            handle {
                val requestStart = TimeSource.Monotonic.markNow()
                val instance = getLoadInstance(call) ?: return@handle
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val (_, forwardTime) = measureTimedValue {
                    forward(call, instance.httpClient, instance.config)
                }
                Log.i { "[LOAD-DRIVER-TIMING] instance=${instance.id} path=$path forward=$forwardTime total=${requestStart.elapsedNow()}" }
            }
        }

        webSocket("/load/{instanceIndex}/{path...}") {
            val instance = getLoadInstanceOrClose(this, call) ?: return@webSocket
            val instanceIndex = call.parameters["instanceIndex"] ?: ""
            val targetUrl = buildWsTargetUrl(call, instance.config, "/load/$instanceIndex")

            forwardWs(this, instance.client, targetUrl, instance.config)
        }

        get("/loaddriver-api/{instanceIndex}/authenticate") {
            val instance = getLoadInstance(call) ?: return@get
            authenticate(call, instance.client)
        }

        get("/loaddriver-api/{instanceIndex}/discover") {
            val instance = getLoadInstance(call) ?: return@get
            discover(call, instance.client)
        }

        get("/loaddriver-api/{instanceIndex}/register") {
            val instance = getLoadInstance(call) ?: return@get
            register(call, instance.client)
        }

        get("/loaddriver-api/{instanceIndex}/reset") {
            val instance = getLoadInstance(call) ?: return@get
            reset(call, instance.client)
        }

        get("/loaddriver-api/{instanceIndex}/config") {
            val instance = getLoadInstance(call) ?: return@get
            call.respondText(
                Json.encodeToString(instance.config),
                ContentType.Application.Json,
            )
        }

        get("/loaddriver-api/{instanceIndex}/removeAuth") {
            val instance = getLoadInstance(call) ?: return@get
            AuthenticationStorageImpl(instance.store)
                .clear()

            call.respondText(
                "Authentication removed",
                ContentType.Application.Json,
            )
        }
    }
}

private fun loadInstanceConfig(id: Int): SdkInstanceConfig {
    return if (instanceConfigFile?.exists() == true) {
        Log.i { "[LOAD-DRIVER-CONFIG] Loading config from ${instanceConfigFile.absolutePath}" }
        val props = Properties().apply {
            instanceConfigFile.inputStream().use { load(it) }
        }
        buildConfigFromProperties(props, id)
    } else {
        Log.i { "[LOAD-DRIVER-CONFIG] No INSTANCE_CONFIG file, using global env for instance $id" }
        SdkInstanceConfig.fromEnv()
    }
}

private fun buildConfigFromProperties(props: Properties, id: Int): SdkInstanceConfig {
    fun getConfigValue(key: String): String? {
        return props.getProperty("${key}_$id")
            ?: props.getProperty("${key}_1")
            ?: System.getenv(key)
    }

    return SdkInstanceConfig(
        fachdienstUrl = getConfigValue("FACHDIENST_URL")
            ?: error("Missing FACHDIENST_URL for instance $id"),
        smbKeystoreFile = getConfigValue("SMB_KEYSTORE_FILE") ?: "",
        smbKeystoreB64 = getConfigValue("SMB_KEYSTORE_B64") ?: "",
        smbKeystoreAlias = getConfigValue("SMB_KEYSTORE_ALIAS") ?: "",
        smbKeystorePassword = getConfigValue("SMB_KEYSTORE_PASSWORD") ?: "",
        smcbBaseUrl = getConfigValue("SMCB_BASE_URL") ?: "",
        smcbCardHandle = getConfigValue("SMCB_CARD_HANDLE") ?: "",
        smcbClientSystemId = getConfigValue("SMCB_CLIENT_SYSTEM_ID") ?: "",
        smcbMandantId = getConfigValue("SMCB_MANDANT_ID") ?: "",
        smcbUserId = getConfigValue("SMCB_USER_ID") ?: "",
        smcbWorkspaceId = getConfigValue("SMCB_WORKSPACE_ID") ?: "",
        aslProdEnv = getConfigValue("ASL_PROD")?.toBoolean() ?: true,
        poppToken = getConfigValue("POPP_TOKEN") ?: "",
    )
}

private suspend fun getLoadInstance(call: ApplicationCall): SdkInstance? {
    val index = call.parameters["instanceIndex"]?.toIntOrNull()
    if (index == null) {
        call.respond(HttpStatusCode.BadRequest, "Missing instanceIndex")
        return null
    }
    val instance = loadInstances[index]
    if (instance == null) {
        call.respond(HttpStatusCode.NotFound, "instance $index not found")
        return null
    }
    if (instance.state != InstanceState.READY) {
        initializeInstance(instance.id, instance)
    }
    return instance
}

private suspend fun getLoadInstanceOrClose(
    session: DefaultWebSocketSession,
    call: ApplicationCall,
): SdkInstance? {
    val instanceIndex = call.parameters["instanceIndex"]?.toIntOrNull()
    if (instanceIndex == null) {
        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing instanceIndex"))
        return null
    }

    val instance = loadInstances[instanceIndex]
    if (instance == null) {
        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "instance $instanceIndex not found"))
        return null
    }

    if (instance.state != InstanceState.READY) {
        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "instance $instanceIndex is in state ${instance.state}"))
        return null
    }

    return instance
}

private suspend fun parseCreateInstancesRequest(call: ApplicationCall): CreateInstancesRequest {
    return try {
        call.receive<CreateInstancesRequest>()
    } catch (ex: Exception) {
        Log.e { "Failed to parse request: ${ex.message}" }
        CreateInstancesRequest(
            count = call.parameters["count"]?.toIntOrNull(),
            autoInit = call.parameters["autoInit"]?.toBoolean() ?: true,
        )
    }
}

private fun createInstance(
    index: Int,
    providedConfigs: List<SdkInstanceConfig>?,
    created: MutableList<Int>,
): Int? {
    val providedConfig = providedConfigs?.getOrNull(index)
    val id = if (providedConfig?.id != null) {
        loadInstanceIdGenerator.updateAndGet { maxOf(it, providedConfig.id) }
        providedConfig.id
    } else {
        loadInstanceIdGenerator.incrementAndGet()
    }
    if (loadInstances.containsKey(id)) {
        Log.w { "[LOAD-DRIVER-CONFIG] Instance $id already exists, skipping" }
        return null
    }
    val config = when {
        providedConfig != null -> mergeWithEnvFallback(providedConfig)
        else -> loadInstanceConfig(id)
    }
    val store = InMemoryStorage()
    val instance = SdkInstance(id, config, store)
    loadInstances[id] = instance
    created += id
    return id
}

private suspend fun initializeInstance(id: Int, instance: SdkInstance) {
    val initStart = TimeSource.Monotonic.markNow()
    instance.state = InstanceState.INITIALIZING

    if (!performDiscover(id, instance)) return
    if (!performRegister(id, instance)) return

    instance.state = InstanceState.READY
    Log.i { "[LOAD-DRIVER-TIMING] instance=$id READY total_init=${initStart.elapsedNow()}" }
}

private suspend fun performDiscover(id: Int, instance: SdkInstance): Boolean {
    val (result, time) = measureTimedValue { instance.client.discover() }
    return result.onFailure { ex ->
        Log.i { "[LOAD-DRIVER-TIMING] instance=$id discover FAILED in $time: ${ex.message}" }
        instance.state = InstanceState.FAILED
        instance.error = "discover failed: ${ex.message}"
    }.isSuccess.also {
        if (it) Log.i { "[LOAD-DRIVER-TIMING] instance=$id discover OK in $time" }
    }
}

private suspend fun performRegister(id: Int, instance: SdkInstance): Boolean {
    val (result, time) = measureTimedValue { instance.client.register() }
    return result.onFailure { ex ->
        Log.i { "[LOAD-DRIVER-TIMING] instance=$id register FAILED in $time: ${ex.message}" }
        instance.state = InstanceState.FAILED
        instance.error = "register failed: ${ex.message}"
    }.isSuccess.also {
        if (it) Log.i { "[LOAD-DRIVER-TIMING] instance=$id register OK in $time" }
    }
}

private fun buildCreateResponse(created: List<Int>, autoInit: Boolean) = buildJsonObject {
    put("created", JsonPrimitive(created.size))
    put("ids", JsonArray(created.map { JsonPrimitive(it) }))
    put("autoInit", JsonPrimitive(autoInit))
}

private fun mergeWithEnvFallback(provided: SdkInstanceConfig): SdkInstanceConfig {
    fun env(key: String) = System.getenv(key) ?: ""

    val keystoreFile = provided.smbKeystoreFile.ifBlank { "" }
    val keystoreB64 = provided.smbKeystoreB64.ifBlank { "" }

    val (resolvedFile, resolvedB64) = when {
        keystoreFile.isNotBlank() -> keystoreFile to ""

        keystoreB64.isNotBlank() -> "" to keystoreB64

        else -> {
            val envFile = env("SMB_KEYSTORE_FILE")
            val envB64 = env("SMB_KEYSTORE_B64")
            when {
                envFile.isNotBlank() -> envFile to ""
                else -> "" to envB64
            }
        }
    }

    return provided.copy(
        smbKeystoreFile = resolvedFile,
        smbKeystoreB64 = resolvedB64,
        fachdienstUrl = provided.fachdienstUrl?.ifBlank { env("FACHDIENST_URL") } ?: env("FACHDIENST_URL").ifBlank { null },
        smbKeystoreAlias = provided.smbKeystoreAlias.ifBlank { env("SMB_KEYSTORE_ALIAS") },
        smbKeystorePassword = provided.smbKeystorePassword.ifBlank { env("SMB_KEYSTORE_PASSWORD") },
        smcbBaseUrl = provided.smcbBaseUrl.ifBlank { env("SMCB_BASE_URL") },
        smcbCardHandle = provided.smcbCardHandle.ifBlank { env("SMCB_CARD_HANDLE") },
        smcbClientSystemId = provided.smcbClientSystemId.ifBlank { env("SMCB_CLIENT_SYSTEM_ID") },
        smcbMandantId = provided.smcbMandantId.ifBlank { env("SMCB_MANDANT_ID") },
        smcbUserId = provided.smcbUserId.ifBlank { env("SMCB_USER_ID") },
        smcbWorkspaceId = provided.smcbWorkspaceId.ifBlank { env("SMCB_WORKSPACE_ID") },
        poppToken = provided.poppToken.ifBlank { env("POPP_TOKEN") },
        aslProdEnv = System.getenv("ASL_PROD")?.toBoolean() ?: provided.aslProdEnv,
    )
}
