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
package org.conscrypt

import de.gematik.zeta.logging.Log
import javax.net.ssl.SSLSession
import kotlin.ByteArray

/**
 * Reads Conscrypt's stapled OCSP response.
 */
internal object ZetaConscryptStaple {

    /**
     * @return the session's status responses, or `null` if it did not come from Conscrypt.
     */
    fun statusResponses(session: SSLSession): List<ByteArray>? =
        try {
            (session as? ConscryptSession)?.statusResponses
        } catch (error: LinkageError) {
            Log.w(error) {
                "ZetaTls: ConscryptSession accessor failed (${error::class.simpleName}: " +
                    "${error.message}) - likely a Conscrypt version mismatch forced by a consumer app"
            }
            null
        }
}
