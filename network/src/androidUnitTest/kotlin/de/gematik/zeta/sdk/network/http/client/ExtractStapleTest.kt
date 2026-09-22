/*
 *
 *  * #%L
 *  * ZETA-Client
 *  * %%
 *  * (C) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH
 *  * %%
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *  *
 *  * ******
 *  *
 *  * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 *  * #L%
 *
 */
package de.gematik.zeta.sdk.network.http.client

import de.gematik.zeta.logging.Log
import de.gematik.zeta.logging.ZetaLogLevel
import de.gematik.zeta.logging.ZetaLogger
import io.mockk.mockk
import org.conscrypt.conscryptSessionReturning
import javax.net.ssl.SSLSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractStapleTest {

    private val warnings = mutableListOf<String>()

    @BeforeTest
    fun installRecordingLogger() {
        Log.setLogLevel(ZetaLogLevel.DEBUG)
        Log.setLogger(
            object : ZetaLogger {
                override fun d(tag: String?, message: () -> String, throwable: Throwable?) = Unit
                override fun i(tag: String?, message: () -> String, throwable: Throwable?) = Unit
                override fun w(tag: String?, message: () -> String, throwable: Throwable?) {
                    warnings += message()
                }
                override fun e(tag: String?, message: () -> String, throwable: Throwable?) = Unit
            },
        )
    }

    @AfterTest
    fun resetLogger() {
        Log.clearDestinations()
    }

    @Test
    fun returnsFirstStatusResponseForConscryptSession() {
        val staple = byteArrayOf(0x30, 0x03, 0x0A, 0x01, 0x00)
        val other = byteArrayOf(0x30, 0x01, 0x00)

        val session = conscryptSessionReturning(listOf(staple, other))

        assertContentEquals(staple, extractStaple(session))
    }

    @Test
    fun returnsNullWhenConscryptSessionHasNoStatusResponses() {
        val session = conscryptSessionReturning(emptyList())

        assertNull(extractStaple(session))
    }

    @Test
    fun returnsNullAndWarnsWhenSessionIsNotConscrypt() {
        val session = mockk<SSLSession>()

        assertNull(extractStaple(session))
        assertTrue(
            warnings.any { it.contains("not a ConscryptSession") },
            "expected a warning about a non-Conscrypt session, got: $warnings",
        )
    }
}
