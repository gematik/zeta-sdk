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

package de.gematik.zeta.sdk.network.http.client

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.options
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking

public object HttpClientSync {
    private fun HttpRequestBuilder.applyHeaders(headers: Map<String, String>) {
        headers.forEach { (key, value) -> this.headers.append(key, value) }
    }

    public fun HttpClient.get(url: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        HttpResponseWrapper.from(get(url) { applyHeaders(header) })
    }

    public fun HttpClient.post(url: String, body: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        HttpResponseWrapper.from(
            post(url) {
                contentType(ContentType.Application.Json)
                applyHeaders(header)
                setBody(body)
            },
        )
    }

    public fun HttpClient.put(url: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        HttpResponseWrapper.from(put(url) { applyHeaders(header) })
    }

    public fun HttpClient.patch(url: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        HttpResponseWrapper.from(patch(url) { applyHeaders(header) })
    }

    public fun HttpClient.options(url: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        HttpResponseWrapper.from(options(url) { applyHeaders(header) })
    }

    public fun HttpClient.head(url: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        HttpResponseWrapper.from(head(url) { applyHeaders(header) })
    }

    public fun HttpClient.delete(url: String, header: Map<String, String>): HttpResponseWrapper = runBlocking {
        HttpResponseWrapper.from(delete(url) { applyHeaders(header) })
    }
}

public data class HttpResponseWrapper(val status: Int, val headers: Map<String, String>, val body: String) {
    public companion object {
        public suspend fun from(response: HttpResponse): HttpResponseWrapper =
            HttpResponseWrapper(
                status = response.status.value,
                headers = response.headers.entries().associate { it.key to it.value.joinToString(",") },
                body = response.bodyAsBytes().decodeToString(),
            )
    }
}
