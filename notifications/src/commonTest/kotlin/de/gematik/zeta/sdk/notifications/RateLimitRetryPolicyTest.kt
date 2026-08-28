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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RateLimitRetryPolicyTest {
    @Test
    fun defaultsToTwoRetriesAnd500msBackoff() {
        val policy = RateLimitRetryPolicy()

        assertEquals(2, policy.maxRetries)
        assertEquals(500L, policy.initialBackoffMs)
    }

    @Test
    fun acceptsZeroRetriesAndZeroBackoff() {
        val policy = RateLimitRetryPolicy(maxRetries = 0, initialBackoffMs = 0)

        assertEquals(0, policy.maxRetries)
        assertEquals(0L, policy.initialBackoffMs)
    }

    @Test
    fun rejectsNegativeMaxRetries() {
        assertFailsWith<IllegalArgumentException> { RateLimitRetryPolicy(maxRetries = -1) }
    }

    @Test
    fun rejectsNegativeInitialBackoff() {
        assertFailsWith<IllegalArgumentException> { RateLimitRetryPolicy(initialBackoffMs = -1) }
    }
}
