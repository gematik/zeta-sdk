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

import de.gematik.zeta.sdk.storage.SdkStorage

/**
 * Test util.
 */
class RecordingDoneHandler(private val needToHandle: FlowNeed) : CapabilityHandler {
    var called: Boolean = false
    override fun canHandle(need: FlowNeed): Boolean = need == needToHandle
    override suspend fun handle(
        need: FlowNeed,
        ctx: FlowContext,
    ): CapabilityResult = CapabilityResult.Done.also { called = true }
}

/**
 * Test util.
 */
class InMemoryStorageTestImpl : SdkStorage {
    override suspend fun put(key: String, value: String) {
        TODO("Not yet implemented")
    }

    override suspend fun get(key: String): String? {
        return ""
    }

    override suspend fun remove(key: String) {
        TODO("Not yet implemented")
    }

    override suspend fun clear() {
        TODO("Not yet implemented")
    }
}
