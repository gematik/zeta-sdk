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

package de.gematik.zeta.sdk.tpm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SoftwareCryptoProviderJvmTest {

    private class InMemoryStorage : TpmStorage {
        var clientPublic: String? = null
        var clientPrivate: String? = null
        var dpopPublic: String? = null
        var dpopPrivate: String? = null
        var saveDpopCount = 0

        override suspend fun saveClientKeys(publicKey: String, privateKey: String) {
            clientPublic = publicKey
            clientPrivate = privateKey
        }

        override suspend fun saveDpopKeys(publicKey: String, privateKey: String) {
            saveDpopCount++
            dpopPublic = publicKey
            dpopPrivate = privateKey
        }

        override suspend fun getClientPublicKey(): String? = clientPublic
        override suspend fun getClientPrivateKey(): String? = clientPrivate
        override suspend fun getDpopPublicKey(): String? = dpopPublic
        override suspend fun getDpopPrivateKey(): String? = dpopPrivate
        override suspend fun getClientKeyCreatedAt(): String? = null

        override suspend fun deleteDpopKeys() {
            dpopPublic = null
            dpopPrivate = null
        }

        override suspend fun deleteAllDpopKeys() = deleteDpopKeys()

        override suspend fun clear() {
            clientPublic = null
            clientPrivate = null
            deleteDpopKeys()
        }
    }

    @Test
    fun generateDpopKey_generatesAndPersists_whenStorageIsEmpty() = runTest {
        val storage = InMemoryStorage()

        jvmSoftwareCryptoProvider(storage).generateDpopKey()

        assertEquals(1, storage.saveDpopCount)
        assertNotNull(storage.dpopPublic)
        assertNotNull(storage.dpopPrivate)
    }

    @Test
    fun generateDpopKey_reusesPersistedKey_onSecondCall() = runTest {
        val storage = InMemoryStorage()
        val provider = jvmSoftwareCryptoProvider(storage)

        provider.generateDpopKey()
        val firstPublic = storage.dpopPublic
        provider.generateDpopKey()

        assertEquals(1, storage.saveDpopCount)
        assertEquals(firstPublic, storage.dpopPublic)
    }

    @Test
    fun generateDpopKey_regenerates_whenOnlyPrivateKeyIsStored() = runTest {
        val storage = InMemoryStorage()
        val provider = jvmSoftwareCryptoProvider(storage)
        provider.generateDpopKey()
        storage.dpopPublic = null
        storage.saveDpopCount = 0

        provider.generateDpopKey()

        assertEquals(1, storage.saveDpopCount)
        assertNotNull(storage.dpopPublic)
    }

    @Test
    fun generateDpopKey_regenerates_whenStoredKeysAreCorrupt() = runTest {
        val storage = InMemoryStorage().apply {
            dpopPublic = "not-a-key"
            dpopPrivate = "not-a-key"
        }

        jvmSoftwareCryptoProvider(storage).generateDpopKey()

        assertEquals(1, storage.saveDpopCount)
        assertNotEquals("not-a-key", storage.dpopPublic)
        assertNotEquals("not-a-key", storage.dpopPrivate)
    }

    @Test
    fun signWithDpopKey_throws_whenNoKeyIsStored() = runTest {
        val resource = "https://rs.example"

        val ex = assertFailsWith<IllegalStateException> {
            jvmSoftwareCryptoProvider(InMemoryStorage()).signWithDpopKey(byteArrayOf(1, 2, 3), resource)
        }

        assertTrue(ex.message!!.contains(resource))
    }

    @Test
    fun signWithDpopKey_returnsJoseSignature_afterKeyGeneration() = runTest {
        val provider = jvmSoftwareCryptoProvider(InMemoryStorage())
        provider.generateDpopKey()

        val signature = provider.signWithDpopKey("header.payload".encodeToByteArray(), "https://rs.example")

        assertEquals(64, signature.size)
    }
}
