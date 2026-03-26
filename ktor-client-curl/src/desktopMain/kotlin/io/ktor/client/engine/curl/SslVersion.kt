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

package io.ktor.client.engine.curl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import libcurl.*

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
public enum class SslVersion(internal val curlValue: Long) {
    DEFAULT(CURL_SSLVERSION_DEFAULT.toLong()),
    TLS_1_2(CURL_SSLVERSION_TLSv1_2.toLong()),
    TLS_1_3(CURL_SSLVERSION_TLSv1_3.toLong()),
}
