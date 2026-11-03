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

package de.gematik.zeta.sdk.storage

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Exercises [getOrCreateDek] against a real (Robolectric-provided) `SharedPreferences`-backed
 * [Context], with a fake [DekWrapper] standing in for the real Android Keystore.
 * [AndroidKeystoreDekWrapper] itself can't be exercised here - see its kdoc.
 */
@RunWith(RobolectricTestRunner::class)
class GetOrCreateDekTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `generates and persists a new DEK on first use`() {
        val wrapper = FakeDekWrapper()

        val dek = getOrCreateDek(context, wrapper)

        assertEquals(32, dek.size)
        assertEquals(1, wrapper.wrapCount)
    }

    @Test
    fun `reuses the persisted DEK on a later call instead of generating a new one`() {
        val wrapper = FakeDekWrapper()
        val first = getOrCreateDek(context, wrapper)

        val second = getOrCreateDek(context, wrapper)

        assertContentEquals(first, second)
        assertEquals(1, wrapper.wrapCount)
        assertEquals(1, wrapper.unwrapCount)
    }

    @Test
    fun `regenerates the DEK when the stored one can no longer be unwrapped`() {
        val original = getOrCreateDek(context, FakeDekWrapper())

        val brokenWrapper = FakeDekWrapper(failUnwrap = true)
        val regenerated = getOrCreateDek(context, brokenWrapper)

        assertFalse(original.contentEquals(regenerated))
        assertEquals(1, brokenWrapper.unwrapCount)
        assertEquals(1, brokenWrapper.wrapCount)
    }

    @Test
    fun `a regenerated DEK is itself persisted for the next call`() {
        getOrCreateDek(context, FakeDekWrapper())
        val regenerated = getOrCreateDek(context, FakeDekWrapper(failUnwrap = true))

        val reread = getOrCreateDek(context, FakeDekWrapper())

        assertContentEquals(regenerated, reread)
    }
}

private class FakeDekWrapper(private val failUnwrap: Boolean = false) : DekWrapper {
    var wrapCount = 0
        private set
    var unwrapCount = 0
        private set

    override fun wrap(dek: ByteArray): ByteArray {
        wrapCount++
        return byteArrayOf(MARKER) + dek
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        unwrapCount++
        check(!failUnwrap) { "Simulated Keystore unwrap failure" }
        require(wrapped.isNotEmpty() && wrapped[0] == MARKER) { "Not wrapped by this fake" }
        return wrapped.copyOfRange(1, wrapped.size)
    }

    private companion object {
        const val MARKER = 0x42.toByte()
    }
}
