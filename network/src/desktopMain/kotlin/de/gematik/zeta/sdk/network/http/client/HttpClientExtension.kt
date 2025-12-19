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

@file:OptIn(ExperimentalNativeApi::class)

package de.gematik.zeta.sdk.network.http.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.headers
import kotlinx.coroutines.runBlocking
import kotlin.experimental.ExperimentalNativeApi

public object HttpClientExtension {

    public fun HttpClient.httpGet(url: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        val resp = get(url) {
            header.entries.forEach {
                headers.append(it.key, it.value)
            }
        }
        HttpResponseWrapper.from(resp)
    }

    public fun HttpClient.httpPost(url: String, body: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        val resp = post(url) {
            header.entries.forEach {
                headers.append(it.key, it.value)
            }
            setBody(body)
        }
        HttpResponseWrapper.from(resp)
    }
}

public data class HttpResponseWrapper(val status: Int, val headers: Map<String, String>, val body: String) {
    public companion object {
        public suspend fun from(response: HttpResponse): HttpResponseWrapper =
            HttpResponseWrapper(
                status = response.status.value,
                headers = response.headers.entries().associate { it.key to it.value.joinToString(",") },
                body = response.bodyAsText(),
            )
    }
}
