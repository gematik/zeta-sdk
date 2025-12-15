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

import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.tpm.Tpm
import de.gematik.zeta.sdk.tpm.TpmStorageImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TpmProviderTest {

    @Test
    fun generateClientInstanceKey_returnSameKeys() = runTest {
        val storage = InMemoryStorage()
        val provider = Tpm.provider(TpmStorageImpl(storage))

        val key1 = provider.generateClientInstanceKey()
        delay(1000)
        val key2 = provider.generateClientInstanceKey()

        assertEquals(key1.encoded, key2.encoded)
        assertEquals(key1.jwk, key2.jwk)
    }
}
