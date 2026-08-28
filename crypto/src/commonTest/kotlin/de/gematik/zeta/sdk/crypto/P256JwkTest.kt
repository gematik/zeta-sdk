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

package de.gematik.zeta.sdk.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class P256JwkTest {

    private val x = ByteArray(32) { it.toByte() }
    private val y = ByteArray(32) { (it + 32).toByte() }

    private val xB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val yB64 = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"

    // base64url(SHA-256({"crv":"P-256","kty":"EC","x":<xB64>,"y":<yB64>})), computed independently.
    private val expectedKid = "0r62zgBj277RicA3LnaBKQ5_9RCDIomrlbpWjO0QTG0"

    @Test
    fun p256CoordinatesToJwk_populatesAllFields() {
        val jwk = p256CoordinatesToJwk(x, y)

        assertEquals("EC", jwk.kty)
        assertEquals("P-256", jwk.crv)
        assertEquals("ES256", jwk.alg)
        assertEquals("sig", jwk.use)
        assertEquals(xB64, jwk.x)
        assertEquals(yB64, jwk.y)
    }

    @Test
    fun p256CoordinatesToJwk_derivesKidFromCanonicalJson() {
        val jwk = p256CoordinatesToJwk(x, y)

        assertEquals(expectedKid, jwk.kid)
        // base64url(SHA-256) without padding is always 43 characters.
        assertEquals(43, jwk.kid.length)
    }

    @Test
    fun p256CoordinatesToJwk_isDeterministic() {
        assertEquals(p256CoordinatesToJwk(x, y), p256CoordinatesToJwk(x, y))
    }

    @Test
    fun p256CoordinatesToJwk_differentCoordinatesProduceDifferentKid() {
        val swapped = p256CoordinatesToJwk(y, x)

        assertNotEquals(expectedKid, swapped.kid)
    }

    @Test
    fun p256UncompressedPointToJwk_validPointMatchesCoordinateForm() {
        val point = byteArrayOf(0x04.toByte()) + x + y

        val fromPoint = p256UncompressedPointToJwk(point)

        assertEquals(p256CoordinatesToJwk(x, y), fromPoint)
        assertEquals(expectedKid, fromPoint.kid)
    }

    @Test
    fun p256UncompressedPointToJwk_wrongLengthThrows() {
        val tooShort = byteArrayOf(0x04.toByte()) + x // 33 bytes

        assertFailsWith<IllegalArgumentException> { p256UncompressedPointToJwk(tooShort) }
    }

    @Test
    fun p256UncompressedPointToJwk_missingUncompressedPrefixThrows() {
        val badPrefix = byteArrayOf(0x03.toByte()) + x + y // 65 bytes, wrong prefix

        assertFailsWith<IllegalArgumentException> { p256UncompressedPointToJwk(badPrefix) }
    }

    @Test
    fun sha256PublicKeyPoint_knownAnswer() {
        val point = ByteArray(65).also { it[0] = 0x04 }
        val expectedHex = "59ef1a5a00f35b1a722da56ca70b52a721f33998634d4fa4259301f170f7b6bd"
        val actual = sha256PublicKeyPoint(point)
        assertContentEquals(expectedHex.hexToByteArray(), actual)
    }

    @Test
    fun sha256PublicKeyPoint_rejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> {
            sha256PublicKeyPoint(ByteArray(64).also { it[0] = 0x04 })
        }
    }

    @Test
    fun sha256PublicKeyPoint_rejectsWrongPrefix() {
        assertFailsWith<IllegalArgumentException> {
            sha256PublicKeyPoint(ByteArray(65))
        }
    }
}
