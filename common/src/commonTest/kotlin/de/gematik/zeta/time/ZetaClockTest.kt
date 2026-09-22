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

package de.gematik.zeta.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ZetaClockTest {

    @Test
    fun lambdaClock_returnsConfiguredInstant() {
        val expected = Instant.fromEpochSeconds(1_800_000_000L)
        val clock = ZetaClock { expected }

        val actual = clock.now()

        assertEquals(expected, actual)
    }

    @Test
    fun lambdaClock_returnsExpectedEpochSeconds() {
        val expectedEpochSeconds = 1_800_000_000L
        val clock = ZetaClock {
            Instant.fromEpochSeconds(expectedEpochSeconds)
        }

        val actual = clock.now().epochSeconds

        assertEquals(expectedEpochSeconds, actual)
    }

    @Test
    fun systemClock_returnsCurrentTime() {
        val before = Clock.System.now()

        val actual = SystemZetaClock.now()

        val after = Clock.System.now()

        assertTrue(actual >= before)
        assertTrue(actual <= after)
    }
}
