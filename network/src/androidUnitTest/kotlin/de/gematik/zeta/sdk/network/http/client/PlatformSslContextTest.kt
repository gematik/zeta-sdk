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

import de.gematik.zeta.sdk.network.http.client.config.tls.ZetaTlsProtocols.TLS_1_2
import org.conscrypt.Conscrypt
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Host-JVM unit tests run without Conscrypt's native library, which is exactly the situation
 * the SDK must refuse on a device: OCSP stapling cannot be verified without Conscrypt.
 */
class PlatformSslContextTest {

    @Test
    fun androidRuntime_withoutConscrypt_refusesToBuildTlsStack() {
        assertFalse(Conscrypt.isAvailable(), "this test relies on Conscrypt being unavailable on the host")

        val error = assertFailsWith<SSLException> {
            createPlatformSslContext(onAndroidRuntime = true)
        }

        assertEquals(true, error.message?.contains("Conscrypt is unavailable"), "got: ${error.message}")
    }

    @Test
    fun hostJvm_usesPlatformProvider() {
        val context = createPlatformSslContext(onAndroidRuntime = false)

        assertEquals(TLS_1_2, context.protocol)
        assertNotEquals("Conscrypt", context.provider.name)
    }
}
