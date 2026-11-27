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

import de.gematik.zeta.driver.model.InstanceState
import de.gematik.zeta.driver.model.SdkInstance
import de.gematik.zeta.driver.model.SdkInstanceConfig
import de.gematik.zeta.logging.Log
import de.gematik.zeta.sdk.ZetaSdk.forget
import de.gematik.zeta.sdk.storage.InMemoryStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

public open class LoadInstanceManager(
    private val configProvider: ConfigProvider = EnvConfigProvider(),
    private val poppTokenSource: PoppTokenSource = defaultPoppTokenSource,
) {
    private val instances = ConcurrentHashMap<Int, SdkInstance>()
    private val idGenerator = AtomicInteger(0)

    public suspend fun createInstances(
        count: Int,
        providedConfigs: List<SdkInstanceConfig>? = null,
    ): List<Int> {
        val created = mutableListOf<Int>()
        for (index in 0 until count.coerceAtLeast(0)) {
            createInstance(index, providedConfigs, created)
        }
        return created
    }

    public suspend fun createInstance(
        index: Int,
        providedConfigs: List<SdkInstanceConfig>?,
        created: MutableList<Int>,
    ): Int? {
        val providedConfig = providedConfigs?.getOrNull(index)
        val id = if (providedConfig?.id != null) {
            idGenerator.updateAndGet { maxOf(it, providedConfig.id) }
            providedConfig.id
        } else {
            idGenerator.incrementAndGet()
        }

        if (instances.containsKey(id)) {
            return skipExisting(id)
        }

        val baseConfig = when {
            providedConfig != null -> configProvider.mergeWithEnvFallback(providedConfig)
            else -> configProvider.loadConfig(id)
        }
        val config = baseConfig.withPoppToken(poppTokenSource.generatedTokenFor(baseConfig))

        val store = InMemoryStorage()
        val instance = SdkInstance(id, config, store)
        if (instances.putIfAbsent(id, instance) != null) return skipExisting(id)
        created += id
        return id
    }

    private fun skipExisting(id: Int): Int? {
        Log.w { "[LOAD-DRIVER-CONFIG] Instance $id already exists, skipping" }
        return null
    }

    public open suspend fun initializeInstance(id: Int): Boolean {
        val instance = instances[id] ?: return false
        val initStart = TimeSource.Monotonic.markNow()
        instance.state = InstanceState.INITIALIZING

        if (!performDiscover(id, instance)) return false
        if (!performRegister(id, instance)) return false

        instance.state = InstanceState.READY
        Log.i { "[LOAD-DRIVER-TIMING] instance=$id READY total_init=${initStart.elapsedNow()}" }
        return true
    }

    public open fun getInstance(id: Int): SdkInstance? = instances[id]

    public open fun listInstances(): List<SdkInstance> = instances.values.sortedBy { it.id }

    public open suspend fun deleteInstances(ids: List<Int>?): List<Int> {
        return if (ids.isNullOrEmpty()) {
            val keys = instances.keys.toList()
            instances.values.forEach { instance ->
                try {
                    instance.client.forget()
                    instance.client.close()
                    instance.httpClient.close()
                } catch (e: Exception) {
                    Log.w { "Failed to close httpClient: ${e.message}" }
                }
            }
            instances.clear()
            idGenerator.set(0)
            keys
        } else {
            ids.mapNotNull { id ->
                instances.remove(id)?.also { instance ->
                    try {
                        instance.client.forget()
                        instance.client.close()
                        instance.httpClient.close()
                    } catch (e: Exception) {
                        Log.w { "Failed to close httpClient for id $id: ${e.message}" }
                    }
                }?.let { id }
            }
        }
    }

    public fun clear() {
        instances.clear()
        idGenerator.set(0)
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
}
