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

import de.gematik.zeta.sdk.crypto.Hkdf
import kotlinx.serialization.Serializable

/**
 * One month's derived push-encryption material, per `A_27158-01`/`A_27170-01`. [sharedSecret] is
 * the IKM for deriving [yearMonth]'s successor; [aesKey] decrypts notifications sent in
 * [yearMonth].
 */
@Serializable
data class PushKeyMaterial(
    val yearMonth: String,
    val sharedSecret: ByteArray,
    val aesKey: ByteArray,
)

private const val HKDF_OUTPUT_LEN = 64
private const val SHARED_SECRET_LEN = 32

// A_27180 keeps only the current and immediately-previous month (confirmed by the worked example:
// deriving Dec-2023 then Jan-2024 explicitly deletes Nov-2023, not just anything older than that) -
// covering a message encrypted seconds before a month boundary but received seconds after it.
private const val RETENTION_MONTHS = 1

// Defensive only (not spec-mandated): bounds the forward-derivation walk so a malformed or
// hostile `time_message_encrypted` far in the future can't force an unbounded HKDF loop.
private const val MAX_FORWARD_STEPS = 60

private val YEAR_MONTH_REGEX = Regex("""\A\d{4}-(0[1-9]|1[0-2])\z""")

internal fun requireYearMonth(value: String): String {
    require(YEAR_MONTH_REGEX.matches(value)) { "Expected a 'yyyy-MM' value, got '$value'" }
    return value
}

/** HKDF-SHA256(ikm, info = yearMonth) -> 64 bytes; first half is the next shared-secret, second half is the AES-256-GCM key (see the push notification concept by gematik). */
internal fun derivePushKeyMaterial(ikm: ByteArray, yearMonth: String): PushKeyMaterial {
    val okm = Hkdf.hkdfSha256(ikm, info = requireYearMonth(yearMonth).encodeToByteArray(), outLen = HKDF_OUTPUT_LEN)
    return PushKeyMaterial(
        yearMonth = yearMonth,
        sharedSecret = okm.copyOfRange(0, SHARED_SECRET_LEN),
        aesKey = okm.copyOfRange(SHARED_SECRET_LEN, HKDF_OUTPUT_LEN),
    )
}

internal fun nextYearMonth(yearMonth: String): String = fromMonthIndex(toMonthIndex(yearMonth) + 1)

private fun toMonthIndex(yearMonth: String): Int {
    val (year, month) = requireYearMonth(yearMonth).split("-").map { it.toInt() }
    return year * MONTHS_PER_YEAR + (month - 1)
}

private fun fromMonthIndex(index: Int): String {
    val year = index / MONTHS_PER_YEAR
    val month = index % MONTHS_PER_YEAR + 1
    return "$year-${month.toString().padStart(2, '0')}"
}

private const val MONTHS_PER_YEAR = 12

/**
 * The locally retained slice of the monthly key-derivation chain (`A_27177`/`A_27180`): the
 * newest derived month plus the immediately preceding one, to tolerate a message that was
 * encrypted just before a month boundary but received just after it. [entries] is ordered oldest
 * to newest and never empty once created.
 */
@Serializable
data class PushKeyChain(val entries: List<PushKeyMaterial>) {
    init {
        require(entries.isNotEmpty()) { "PushKeyChain must retain at least one key" }
    }

    fun latest(): PushKeyMaterial = entries.last()

    /**
     * Resolves the key for [targetMonth], deriving forward from [latest] as needed, and returns
     * the (possibly advanced and pruned) chain to persist alongside it. Throws if [targetMonth]
     * is older than the oldest retained month (key material already rotated away) or requires
     * more than [MAX_FORWARD_STEPS] derivations to reach.
     */
    fun resolve(targetMonth: String): Pair<PushKeyChain, PushKeyMaterial> {
        requireYearMonth(targetMonth)
        entries.find { it.yearMonth == targetMonth }?.let { return this to it }

        val oldest = entries.first().yearMonth
        check(targetMonth > oldest) {
            "No push encryption key retained for '$targetMonth'; oldest retained month is '$oldest'"
        }

        var chain = this
        repeat(MAX_FORWARD_STEPS) {
            val next = derivePushKeyMaterial(chain.latest().sharedSecret, nextYearMonth(chain.latest().yearMonth))
            chain = PushKeyChain(prune(chain.entries + next))
            if (next.yearMonth == targetMonth) return chain to next // returns the updated chain and the matched key material
        }
        error("Could not derive push encryption key for '$targetMonth' within $MAX_FORWARD_STEPS months")
    }

    private fun prune(candidates: List<PushKeyMaterial>): List<PushKeyMaterial> {
        val newest = toMonthIndex(candidates.last().yearMonth)
        return candidates.filter { toMonthIndex(it.yearMonth) >= newest - RETENTION_MONTHS }
    }
}
