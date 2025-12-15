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

package de.gematik.zeta.sdk.authentication

import kotlin.io.encoding.Base64

fun ByteArray.toBase64(padding: Boolean, urlSafe: Boolean) =
    (if (urlSafe) Base64.UrlSafe else Base64)
        .withPadding(if (padding) Base64.PaddingOption.PRESENT else Base64.PaddingOption.ABSENT)
        .encode(this)

fun String.fromBase64(padding: Boolean, urlSafe: Boolean): ByteArray =
    (if (urlSafe) Base64.UrlSafe else Base64)
        .withPadding(if (padding) Base64.PaddingOption.PRESENT else Base64.PaddingOption.ABSENT)
        .decode(this)
