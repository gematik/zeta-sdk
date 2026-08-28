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

import PublicKeyOut
import de.gematik.zeta.sdk.crypto.KeyPair
import de.gematik.zeta.sdk.crypto.X509PemReader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbstractSoftwareCryptoProviderTest {

    private class RecordingStorage : TpmStorage {
        var deleteDpopKeysCount = 0
        var deleteAllDpopKeysCount = 0

        override suspend fun saveClientKeys(publicKey: String, privateKey: String) = Unit
        override suspend fun saveDpopKeys(publicKey: String, privateKey: String) = Unit
        override suspend fun getClientPublicKey(): String? = null
        override suspend fun getClientPrivateKey(): String? = null
        override suspend fun getDpopPublicKey(): String? = null
        override suspend fun getDpopPrivateKey(): String? = null
        override suspend fun getClientKeyCreatedAt(): String? = null
        override suspend fun deleteDpopKeys() { deleteDpopKeysCount++ }
        override suspend fun deleteAllDpopKeys() { deleteAllDpopKeysCount++ }
        override suspend fun clear() = Unit
    }

    private class TestProvider(
        storage: TpmStorage,
    ) : AbstractSoftwareCryptoProvider(storage, X509PemReader()) {

        override suspend fun signSmb(privateKey: ByteArray, signingInput: ByteArray): ByteArray = byteArrayOf()

        override suspend fun getOrGenerateClientInstancePublicKey(): PublicKeyOut =
            error("not exercised by this test")

        override suspend fun generateDpopKey(): PublicKeyOut =
            error("not exercised by this test")

        override suspend fun signWithClientKey(input: ByteArray): ByteArray =
            error("not exercised by this test")

        override suspend fun signWithDpopKey(input: ByteArray, resource: String): ByteArray =
            error("not exercised by this test")

        fun assignClientKey(keyPair: KeyPair?) {
            clientKey = keyPair
        }

        fun currentClientKey(): KeyPair? = clientKey
    }

    private fun dummyKeyPair(): KeyPair = KeyPair(
        skpi = byteArrayOf(1),
        sec1 = byteArrayOf(2),
        privateKey = byteArrayOf(3),
    )

    @Test
    fun isHardwareBacked_isFalseForSoftwareProvider() = runTest {
        assertFalse(TestProvider(RecordingStorage()).isHardwareBacked())
    }

    @Test
    fun randomUuid_returnsDistinctValues() = runTest {
        val provider = TestProvider(RecordingStorage())

        assertNotEquals(provider.randomUuid(), provider.randomUuid())
    }

    @Test
    fun readSmbCertificate_throwsWhenPemPathIsEmpty() = runTest {
        val exception = assertFailsWith<IllegalStateException> {
            TestProvider(RecordingStorage()).readSmbCertificate("", "alias", "password")
        }
        assertEquals("SM-B certificate .PEM file is empty", exception.message)
    }

    @Test
    fun readSmbCertificateFromBytes_throwsWhenBytesAreEmpty() = runTest {
        val exception = assertFailsWith<IllegalStateException> {
            TestProvider(RecordingStorage()).readSmbCertificateFromBytes(byteArrayOf(), "alias", "password")
        }
        assertEquals("SM-B certificate bytes are empty", exception.message)
    }

    @Test
    fun forget_withoutResource_clearsClientKeyAndDeletesAllDpopKeys() = runTest {
        val storage = RecordingStorage()
        val provider = TestProvider(storage).apply { assignClientKey(dummyKeyPair()) }

        provider.forget()

        assertNull(provider.currentClientKey())
        assertEquals(1, storage.deleteAllDpopKeysCount)
        assertEquals(0, storage.deleteDpopKeysCount)
    }

    @Test
    fun forget_withResource_deletesOnlyResourceDpopKeyAndKeepsClientKey() = runTest {
        val storage = RecordingStorage()
        val clientKey = dummyKeyPair()
        val provider = TestProvider(storage).apply { assignClientKey(clientKey) }

        provider.forget("https://rs.example")

        assertTrue(provider.currentClientKey() === clientKey)
        assertEquals(1, storage.deleteDpopKeysCount)
        assertEquals(0, storage.deleteAllDpopKeysCount)
    }
}
