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
package de.gematik.zeta.sdk.attestation

import de.gematik.zeta.sdk.storage.ExtendedStorage
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttestationStateStorageTest {
    private val storage = InMemoryStorage()
    private val scope = ResourceScope("resource.example.com", listOf("scope-a"))
    private val sut = AttestationStateStorage(storage, scope)

    /** Raw writes must go through the same namespacing the SUT applies. */
    private val namespaced = ExtendedStorage(storage, scope)

    @Test
    fun attestationType_roundTrip_andDefaultNull() = runTest {
        assertNull(sut.getAttestationType())
        sut.setAttestationType(RegisteredAttestationType.APPLE)
        assertEquals(RegisteredAttestationType.APPLE, sut.getAttestationType())
    }

    @Test
    fun attestationType_unknownValue_readsAsNull() = runTest {
        namespaced.put(AttestationStateStorage.KEY_TYPE, "tpm-of-the-future")
        assertNull(sut.getAttestationType())
    }

    @Test
    fun attestedKey_roundTrip_removeDropsKeyIdAndObject() = runTest {
        sut.setKeyId("key-1")
        sut.setAttestationObject("b2JqZWN0")
        assertEquals("key-1", sut.getKeyId())
        assertEquals("b2JqZWN0", sut.getAttestationObject())
        sut.removeAttestedKey()
        assertNull(sut.getKeyId())
        assertNull(sut.getAttestationObject())
    }

    @Test
    fun zetaAttestationToken_roundTrip_remove() = runTest {
        sut.setZetaAttestationToken("jwt")
        assertEquals("jwt", sut.getZetaAttestationToken())
        sut.removeZetaAttestationToken()
        assertNull(sut.getZetaAttestationToken())
    }

    @Test
    fun isolatedPerResourceScope() = runTest {
        val shared = InMemoryStorage()
        val a = AttestationStateStorage(shared, ResourceScope("a.example.com", listOf("s")))
        val b = AttestationStateStorage(shared, ResourceScope("b.example.com", listOf("s")))

        a.setAttestationType(RegisteredAttestationType.APPLE)
        a.setZetaAttestationToken("token-a")

        assertNull(b.getAttestationType())
        assertNull(b.getZetaAttestationToken())

        b.setAttestationType(RegisteredAttestationType.SOFTWARE)
        b.setZetaAttestationToken("token-b")

        assertEquals(RegisteredAttestationType.APPLE, a.getAttestationType())
        assertEquals("token-a", a.getZetaAttestationToken())
        assertEquals(RegisteredAttestationType.SOFTWARE, b.getAttestationType())
        assertEquals("token-b", b.getZetaAttestationToken())
    }

    @Test
    fun clearAll_removesEverything_butNotForeignKeys() = runTest {
        storage.put("foreign", "keep")
        sut.setAttestationType(RegisteredAttestationType.SOFTWARE)
        sut.setKeyId("k")
        sut.setAttestationObject("o")
        sut.setZetaAttestationToken("t")

        sut.clearAll()

        assertNull(sut.getAttestationType())
        assertNull(sut.getKeyId())
        assertNull(sut.getAttestationObject())
        assertNull(sut.getZetaAttestationToken())
        assertEquals("keep", storage.get("foreign"))
    }

    @Test
    fun certificateChain_roundTrip_andRemovedWithAttestedKey() = runTest {
        assertNull(sut.getCertificateChain())
        sut.setCertificateChain(listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        val chain = sut.getCertificateChain()!!
        assertEquals(2, chain.size)
        assertContentEquals(byteArrayOf(1, 2), chain[0])
        assertContentEquals(byteArrayOf(3), chain[1])

        sut.removeAttestedKey()
        assertNull(sut.getCertificateChain())
    }

    @Test
    fun hasCertificateChain_reflectsRawValue_withoutDecoding() = runTest {
        assertFalse(sut.hasCertificateChain())
        namespaced.put(AttestationStateStorage.KEY_CERTIFICATE_CHAIN, "")
        assertFalse(sut.hasCertificateChain())
        namespaced.put(AttestationStateStorage.KEY_CERTIFICATE_CHAIN, "not base64!")
        assertTrue(sut.hasCertificateChain())
        sut.setCertificateChain(listOf(byteArrayOf(1)))
        assertTrue(sut.hasCertificateChain())
        sut.removeAttestedKey()
        assertFalse(sut.hasCertificateChain())
    }

    @Test
    fun clearAll_removesCertificateChain() = runTest {
        sut.setCertificateChain(listOf(byteArrayOf(1)))
        sut.clearAll()
        assertNull(sut.getCertificateChain())
    }

    @Test
    fun setCertificateChain_rejectsEmptyChain() = runTest {
        assertFailsWith<IllegalArgumentException> { sut.setCertificateChain(emptyList()) }
        assertNull(sut.getCertificateChain())
    }
}
