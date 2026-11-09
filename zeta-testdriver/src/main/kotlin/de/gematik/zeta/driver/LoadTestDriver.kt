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

import de.gematik.zeta.driver.core.forward
import de.gematik.zeta.driver.model.CreateInstancesRequest
import de.gematik.zeta.driver.model.InstanceState
import de.gematik.zeta.driver.model.SdkInstance
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.authentication.AuthenticationStorageImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

internal sealed class InstanceResolveResult {
    data object BadIndex : InstanceResolveResult()
    data class NotFound(val index: Int) : InstanceResolveResult()
    data class Success(val instance: SdkInstance) : InstanceResolveResult()
}

private const val MISSING_INSTANCE_INDEX = "Missing instanceIndex"
private const val INSTANCE_NOT_FOUND = "instance %d not found"

// The ´suspend´ is here required, because ´resolveInstance´ is suspended
private suspend fun ApplicationCall.withInstance( // NOSONAR
    instanceManager: LoadInstanceManager,
    block: suspend (SdkInstance) -> Unit,
) {
    when (val r = resolveInstance(parameters["instanceIndex"], instanceManager)) {
        is InstanceResolveResult.BadIndex -> respond(HttpStatusCode.BadRequest, MISSING_INSTANCE_INDEX)
        is InstanceResolveResult.NotFound -> respond(HttpStatusCode.NotFound, INSTANCE_NOT_FOUND.format(r.index))
        is InstanceResolveResult.Success -> block(r.instance)
    }
}

internal suspend fun resolveInstance(
    indexParam: String?,
    manager: LoadInstanceManager,
): InstanceResolveResult {
    val index = indexParam?.toIntOrNull()
        ?: return InstanceResolveResult.BadIndex
    val instance = manager.getInstance(index)
        ?: return InstanceResolveResult.NotFound(index)
    if (instance.state != InstanceState.READY) {
        manager.initializeInstance(instance.id)
    }
    return InstanceResolveResult.Success(instance)
}

internal suspend fun handleCreateInstances(
    count: Int,
    autoInit: Boolean,
    configs: List<SdkInstanceConfig>?,
    manager: LoadInstanceManager,
): JsonObject {
    val created = manager.createInstances(count, configs)
    if (autoInit) {
        val semaphore = Semaphore(50)

        coroutineScope {
            created.forEach { id ->
                launch {
                    semaphore.withPermit {
                        manager.initializeInstance(id)
                    }
                }
            }
        }
    }
    return buildCreateResponse(created, autoInit)
}

internal fun handleListInstances(manager: LoadInstanceManager): JsonArray =
    JsonArray(
        manager.listInstances().map { inst ->
            buildJsonObject {
                put("id", JsonPrimitive(inst.id))
                put("state", JsonPrimitive(inst.state.name))
                inst.error?.let { put("error", JsonPrimitive(it)) }
            }
        },
    )

internal suspend fun handleDeleteInstances(ids: List<Int>?, manager: LoadInstanceManager): List<Int> =
    manager.deleteInstances(ids)

internal fun buildCreateResponse(created: List<Int>, autoInit: Boolean): JsonObject =
    buildJsonObject {
        put("created", JsonPrimitive(created.size))
        put("ids", JsonArray(created.map { JsonPrimitive(it) }))
        put("autoInit", JsonPrimitive(autoInit))
    }

public fun Application.loadTestDriverRouting(
    instanceManager: LoadInstanceManager = LoadInstanceManager(),
) {
    routing {
        loadManagementRoutes(instanceManager)
        loadDriverApiRoutes(instanceManager)
        loadProxyRoutes(instanceManager)
    }
}

private fun Route.loadManagementRoutes(instanceManager: LoadInstanceManager) {
    post("/load/create_instances") {
        val request = parseCreateInstancesRequest(call)
        request.logLevel?.let { Log.setLogLevel(it) }

        val result = handleCreateInstances(
            count = request.count ?: 0,
            autoInit = request.autoInit,
            configs = request.instances,
            manager = instanceManager,
        )
        call.respondJson(result)
    }

    get("/load/list_instances") {
        call.respondJson(handleListInstances(instanceManager))
    }

    delete("/load/delete_instances") {
        val ids = call.parameters.getAll("id")?.mapNotNull { it.toIntOrNull() }
        val removed = handleDeleteInstances(ids, instanceManager)
        call.respondText("""{"removed": ${removed.size}, "ids": $removed}""", ContentType.Application.Json)
    }
}

private fun Route.loadDriverApiRoutes(instanceManager: LoadInstanceManager) {
    get("/loaddriver-api/{instanceIndex}/authenticate") {
        call.withInstance(instanceManager) { authenticate(call, it.client) }
    }
    get("/loaddriver-api/{instanceIndex}/discover") {
        call.withInstance(instanceManager) { discover(call, it.client) }
    }
    get("/loaddriver-api/{instanceIndex}/register") {
        call.withInstance(instanceManager) { register(call, it.client) }
    }
    get("/loaddriver-api/{instanceIndex}/reset") {
        call.withInstance(instanceManager) { reset(call, it.client) }
    }
    get("/loaddriver-api/{instanceIndex}/config") {
        call.withInstance(instanceManager) { call.respondJson(it.config) }
    }
    get("/loaddriver-api/{instanceIndex}/removeAuth") {
        call.withInstance(instanceManager) {
            AuthenticationStorageImpl(it.store, it.resourceScope).clear()
            call.respondText("Authentication removed", ContentType.Application.Json)
        }
    }
}

private fun Route.loadProxyRoutes(instanceManager: LoadInstanceManager) {
    route("/load/{instanceIndex}/{path...}") {
        handle {
            val requestStart = TimeSource.Monotonic.markNow()
            call.withInstance(instanceManager) { instance ->
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val (_, forwardTime) = measureTimedValue {
                    forward(call, instance.config.toProxyTarget(instance.client, instance.httpClient))
                }
                Log.i { "[LOAD-DRIVER-TIMING] instance=${instance.id} path=$path forward=$forwardTime total=${requestStart.elapsedNow()}" }
            }
        }
    }

    webSocket("/load/{instanceIndex}/{path...}") {
        when (val r = resolveInstance(call.parameters["instanceIndex"], instanceManager)) {
            is InstanceResolveResult.BadIndex ->
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, MISSING_INSTANCE_INDEX))
            is InstanceResolveResult.NotFound ->
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "instance ${r.index} not found"))
            is InstanceResolveResult.Success -> when {
                r.instance.state != InstanceState.READY ->
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "instance not ready"))
                else -> {
                    val instanceIndex = call.parameters["instanceIndex"] ?: ""
                    forwardWs(this, r.instance.client, buildWsTargetUrl(call, r.instance.config, "/load/$instanceIndex"), r.instance.config)
                }
            }
        }
    }
}

// The ´suspend´ is here required, because ´respondText´ is suspended
private suspend inline fun <reified T> ApplicationCall.respondJson(value: T) =
    respondText(Json.encodeToString(value), ContentType.Application.Json) // NOSONAR

private suspend fun parseCreateInstancesRequest(call: ApplicationCall): CreateInstancesRequest =
    try {
        call.receive<CreateInstancesRequest>()
    } catch (ex: Exception) {
        Log.e { "Failed to parse request body, falling back to query params: ${ex.message}" }
        CreateInstancesRequest(
            count = call.parameters["count"]?.toIntOrNull(),
            autoInit = call.parameters["autoInit"]?.toBoolean() ?: true,
        )
    }
