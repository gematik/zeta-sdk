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

package de.gematik.zeta.sdk.notifications

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [ISS]/[SHARED_SECRET_2023_10]/[AES_KEY_2023_10]/[SHARED_SECRET_2023_11]/[AES_KEY_2023_11] are
 * gematik's own worked example from the push notification concept (`gem-pushnotifications-concept`,
 * key-derivation section) — a real interop vector, not a self-consistency check: derived and
 * cross-checked independently against RFC 5869 before being hardcoded here.
 */
private const val ISS = "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
private const val SHARED_SECRET_2023_10 = "185fed66ea5cabbe00147bbd298b5dab0ed41b57ab254d35897b3a4504306e3b"
private const val AES_KEY_2023_10 = "3b4adcd58dea98db8e9cb0f5763fcd04fe932d67926cc04b20ba2a2f304ffff9"
private const val SHARED_SECRET_2023_11 = "0c8662d90b04818afb317406fe7fcfcf8d103cd9bc6ad7847890d28620e85ec3"
private const val AES_KEY_2023_11 = "39aa5dacd538f53f4b956d84c9b8f2e26933274d160b9fd1a263a27681c6331b"

class PushKeyChainTest {

    @Test
    fun derivePushKeyMaterial_matchesGematikWorkedExample_forInitialMonth() {
        val material = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")

        assertEquals("2023-10", material.yearMonth)
        assertContentEquals(SHARED_SECRET_2023_10.hexToByteArray(), material.sharedSecret)
        assertContentEquals(AES_KEY_2023_10.hexToByteArray(), material.aesKey)
    }

    @Test
    fun derivePushKeyMaterial_matchesGematikWorkedExample_forChainedMonth() {
        val october = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")

        val november = derivePushKeyMaterial(october.sharedSecret, "2023-11")

        assertContentEquals(SHARED_SECRET_2023_11.hexToByteArray(), november.sharedSecret)
        assertContentEquals(AES_KEY_2023_11.hexToByteArray(), november.aesKey)
    }

    @Test
    fun nextYearMonth_incrementsWithinYear() {
        assertEquals("2023-11", nextYearMonth("2023-10"))
    }

    @Test
    fun nextYearMonth_rollsOverToNextYear() {
        assertEquals("2024-01", nextYearMonth("2023-12"))
    }

    @Test
    fun resolve_returnsExistingEntryWithoutDeriving() {
        val october = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")
        val chain = PushKeyChain(listOf(october))

        val (resolvedChain, material) = chain.resolve("2023-10")

        assertEquals(chain, resolvedChain)
        assertEquals(october, material)
    }

    @Test
    fun resolve_derivesForwardOneMonth() {
        val october = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")
        val chain = PushKeyChain(listOf(october))

        val (resolvedChain, material) = chain.resolve("2023-11")

        assertContentEquals(AES_KEY_2023_11.hexToByteArray(), material.aesKey)
        assertEquals("2023-11", resolvedChain.latest().yearMonth)
        assertEquals(listOf("2023-10", "2023-11"), resolvedChain.entries.map { it.yearMonth })
    }

    /**
     * Gematik's own worked example: starting from October 2023 with no message sent since, the
     * next one arrives in January 2024. The chain must derive November and December along the
     * way, then keep only December (previous) and January (current) — explicitly deleting
     * November, not just "anything older than 2 months".
     */
    @Test
    fun resolve_derivesForwardAcrossAGapAndKeepsOnlyCurrentAndPreviousMonth() {
        val october = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")
        val chain = PushKeyChain(listOf(october))

        val (resolvedChain, material) = chain.resolve("2024-01")

        assertEquals("2024-01", material.yearMonth)
        assertEquals(listOf("2023-12", "2024-01"), resolvedChain.entries.map { it.yearMonth })
    }

    @Test
    fun resolve_throwsWhenTargetMonthIsOlderThanRetainedHistory() {
        val october = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")
        val chain = PushKeyChain(listOf(october))
        val (advanced, _) = chain.resolve("2024-01")

        assertFailsWith<IllegalStateException> { advanced.resolve("2023-10") }
    }

    @Test
    fun resolve_throwsWhenTargetMonthIsUnreachableWithinTheForwardDerivationLimit() {
        val october = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")
        val chain = PushKeyChain(listOf(october))

        assertFailsWith<IllegalStateException> { chain.resolve("2099-01") }
    }

    @Test
    fun resolve_rejectsMalformedTargetMonth() {
        val october = derivePushKeyMaterial(ISS.hexToByteArray(), "2023-10")
        val chain = PushKeyChain(listOf(october))

        assertFailsWith<IllegalArgumentException> { chain.resolve("not-a-month") }
    }

    @Test
    fun pushKeyChain_rejectsEmptyEntries() {
        assertFailsWith<IllegalArgumentException> { PushKeyChain(emptyList()) }
    }
}
