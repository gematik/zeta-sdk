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

import de.gematik.zeta.android.SdkAndroidContext

internal actual fun readP12File(path: String): ByteArray {
    val file = java.io.File(path)
    if (file.exists()) return file.readBytes()

    val context = SdkAndroidContext.get()
    return try {
        context.assets.open(path).readBytes()
    } catch (_: Exception) {
        context.assets.open(path.substringAfterLast("/")).readBytes()
    }
}
