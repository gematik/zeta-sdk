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

import de.gematik.zeta.sdk.crypto.X509PemReader
import de.gematik.zeta.sdk.storage.InMemoryStorage
import de.gematik.zeta.sdk.storage.ResourceScope
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.EC.PublicKey
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural coverage for [NativeSoftwareCryptoProvider], the whyoleg-based software provider
 * shared by every Kotlin/Native target. Tests drive a minimal concrete subclass directly so the
 * shared client/DPoP key handling is exercised uniformly on desktop and apple targets, without
 * going through the apple hardware-backed factory.
 */
@Suppress("FunctionNaming")
class NativeSoftwareCryptoProviderTest {

    private class TestNativeCryptoProvider(
        storage: TpmStorage,
    ) : NativeSoftwareCryptoProvider(storage, X509PemReader()) {
        // SM-B signing is platform-specific and covered by the concrete leaf providers.
        override suspend fun signSmb(privateKey: ByteArray, signingInput: ByteArray): ByteArray =
            byteArrayOf()
    }

    private fun freshStorage(): TpmStorage =
        TpmStorageImpl(InMemoryStorage(), ResourceScope("", emptyList()))

    private fun newProvider(storage: TpmStorage = freshStorage()): TestNativeCryptoProvider =
        TestNativeCryptoProvider(storage)

    private suspend fun verifyEs256(publicKeyRaw: ByteArray, input: ByteArray, signature: ByteArray): Boolean {
        val ec = CryptographyProvider.Default.get(ECDSA)
        val pub = ec.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(PublicKey.Format.RAW.Uncompressed, publicKeyRaw)
        return pub.signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW)
            .tryVerifySignature(input, signature)
    }

    @Test
    fun clientKey_isStableWithinSameInstance() = runTest {
        val provider = newProvider()

        val first = provider.getOrGenerateClientInstancePublicKey()
        val second = provider.getOrGenerateClientInstancePublicKey()

        assertContentEquals(first.encoded, second.encoded)
        assertEquals(first.jwk, second.jwk)
    }

    @Test
    fun clientKey_persistsAcrossInstances() = runTest {
        val storage = freshStorage()

        val generated = newProvider(storage).getOrGenerateClientInstancePublicKey()
        val reloaded = newProvider(storage).getOrGenerateClientInstancePublicKey()

        assertContentEquals(generated.encoded, reloaded.encoded)
        assertEquals(generated.jwk, reloaded.jwk)
    }

    @Test
    fun clientKey_jwkHasEs256SigningFields() = runTest {
        val jwk = newProvider().getOrGenerateClientInstancePublicKey().jwk

        assertEquals("EC", jwk.kty)
        assertEquals("P-256", jwk.crv)
        assertEquals("ES256", jwk.alg)
        assertEquals("sig", jwk.use)
        assertTrue(jwk.kid.isNotEmpty())
        assertTrue(jwk.x.isNotEmpty())
        assertTrue(jwk.y.isNotEmpty())
    }

    @Test
    fun dpopKey_isStableAndPersists() = runTest {
        val storage = freshStorage()

        val first = newProvider(storage).generateDpopKey()
        val second = newProvider(storage).generateDpopKey()

        assertContentEquals(first.encoded, second.encoded)
        assertEquals(first.jwk, second.jwk)
    }

    @Test
    fun signWithClientKey_producesVerifiableEs256Signature() = runTest {
        val provider = newProvider()
        val publicKey = provider.getOrGenerateClientInstancePublicKey()
        val input = "client-signing-input".encodeToByteArray()

        val signature = provider.signWithClientKey(input)

        assertEquals(64, signature.size)
        assertTrue(verifyEs256(publicKey.encoded, input, signature))
    }

    @Test
    fun signWithClientKey_throwsWhenClientKeyNotInitialized() = runTest {
        val provider = newProvider()

        assertFailsWith<IllegalStateException> {
            provider.signWithClientKey("input".encodeToByteArray())
        }
    }

    @Test
    fun signWithDpopKey_producesVerifiableEs256Signature() = runTest {
        val provider = newProvider()
        val publicKey = provider.generateDpopKey()
        val input = "dpop-signing-input".encodeToByteArray()

        val signature = provider.signWithDpopKey(input, "https://rs.example")

        assertEquals(64, signature.size)
        assertTrue(verifyEs256(publicKey.encoded, input, signature))
    }

    @Test
    fun signWithDpopKey_throwsWhenDpopKeyMissing() = runTest {
        val provider = newProvider()

        assertFailsWith<IllegalStateException> {
            provider.signWithDpopKey("input".encodeToByteArray(), "https://rs.example")
        }
    }

    @Test
    fun forget_clearsInMemoryClientKeyButKeepsItInStorage() = runTest {
        val provider = newProvider()
        val original = provider.getOrGenerateClientInstancePublicKey()

        provider.forget()

        // In-memory key was cleared, so signing requires re-initialisation.
        assertFailsWith<IllegalStateException> {
            provider.signWithClientKey("input".encodeToByteArray())
        }
        // Storage was retained, so the same key is reloaded.
        val reloaded = provider.getOrGenerateClientInstancePublicKey()
        assertContentEquals(original.encoded, reloaded.encoded)
    }

    @Test
    fun forget_withResource_deletesDpopKey() = runTest {
        val provider = newProvider()
        val first = provider.generateDpopKey()

        provider.forget("https://rs.example")

        assertFailsWith<IllegalStateException> {
            provider.signWithDpopKey("input".encodeToByteArray(), "https://rs.example")
        }
        val regenerated = provider.generateDpopKey()
        assertFalse(first.encoded.contentEquals(regenerated.encoded))
    }
}
