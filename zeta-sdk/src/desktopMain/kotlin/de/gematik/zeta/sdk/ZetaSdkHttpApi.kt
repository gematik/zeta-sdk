@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@file:Suppress("FunctionNaming")
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

package de.gematik.zeta.sdk

import de.gematik.zeta.sdk.network.http.client.HttpClientAsync.patch
import de.gematik.zeta.sdk.network.http.client.HttpClientAsync.post
import de.gematik.zeta.sdk.network.http.client.HttpClientAsync.put
import de.gematik.zeta.sdk.network.http.client.HttpClientSync.delete
import de.gematik.zeta.sdk.network.http.client.HttpClientSync.get
import de.gematik.zeta.sdk.network.http.client.HttpClientSync.head
import de.gematik.zeta.sdk.network.http.client.HttpClientSync.options
import de.gematik.zeta.sdk.network.http.client.HttpClientSync.patch
import de.gematik.zeta.sdk.network.http.client.HttpClientSync.post
import de.gematik.zeta.sdk.network.http.client.HttpClientSync.put
import de.gematik.zeta.sdk.network.http.client.HttpResponseWrapper
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import interop.ZetaSdk_HttpClient
import interop.ZetaSdk_HttpHeader
import interop.ZetaSdk_HttpRequest
import interop.ZetaSdk_HttpResponse
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import platform.posix.strdup
import kotlin.experimental.ExperimentalNativeApi

private fun CPointer<ZetaSdk_HttpClient>.client() =
    pointed.zetaHttpClient!!.asStableRef<ZetaHttpClient>().get()

private fun ZetaSdk_HttpRequest.parseHeaders(): Map<String, String> =
    headers?.toKList(headersCount)?.associate {
        (it?.key?.toKString() ?: "") to (it?.value?.toKString() ?: "")
    } ?: emptyMap()

private fun HttpResponseWrapper.toNative(): CPointer<ZetaSdk_HttpResponse> {
    val resultHeaders = nativeHeap.allocArray<ZetaSdk_HttpHeader>(headers.size)
    headers.entries.forEachIndexed { index, (key, value) ->
        resultHeaders[index].key = strdup(key)!!
        resultHeaders[index].value = strdup(value)!!
    }
    return nativeHeap.alloc<ZetaSdk_HttpResponse>().apply {
        status = this@toNative.status
        body = strdup(this@toNative.body)!!
        headers = resultHeaders
        headersCount = this@toNative.headers.size
        error = null
    }.ptr
}

private fun Throwable.toNativeError(): CPointer<ZetaSdk_HttpResponse> {
    printStackTrace()
    return nativeHeap.alloc<ZetaSdk_HttpResponse>().apply {
        status = 0
        body = null
        headers = null
        headersCount = 0
        error = strdup(message)!!
    }.ptr
}

private inline fun executeRequest(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    block: (ZetaHttpClient, ZetaSdk_HttpRequest) -> HttpResponseWrapper,
): CPointer<ZetaSdk_HttpResponse>? = try {
    block(httpClient.client(), httpRequest.pointed).toNative()
} catch (e: Throwable) {
    e.toNativeError()
}

@OptIn(DelicateCoroutinesApi::class)
private fun executeRequestAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
    block: suspend (ZetaHttpClient, String, String?, Map<String, String>) -> HttpResponseWrapper,
) {
    try {
        val client = httpClient.pointed.zetaHttpClient!!.asStableRef<ZetaHttpClient>().get()
        val req = httpRequest.pointed
        val url = req.url?.toKString() ?: ""
        val body = req.body?.toKString()
        val headers = req.parseHeaders()

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val response = block(client, url, body, headers)
                onSuccess.invoke(response.toNative())
            } catch (e: Throwable) {
                memScoped { onError.invoke((e.message ?: "unknown").cstr.ptr) }
            }
        }
    } catch (e: Throwable) {
        memScoped { onError.invoke((e.message ?: "unknown").cstr.ptr) }
    }
}

@CName(externName = "ZetaHttpClient_get")
fun ZetaHttpClient_get(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? = executeRequest(httpClient, httpRequest) { client, req ->
    client.delegate.get(req.url?.toKString() ?: "", req.parseHeaders())
}

@CName(externName = "ZetaHttpClient_post")
fun ZetaHttpClient_post(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? = executeRequest(httpClient, httpRequest) { client, req ->
    client.delegate.post(req.url?.toKString() ?: "", req.body?.toKString() ?: "", req.parseHeaders())
}

@CName(externName = "ZetaHttpClient_put")
fun ZetaHttpClient_put(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? = executeRequest(httpClient, httpRequest) { client, req ->
    client.delegate.put(req.url?.toKString() ?: "", req.parseHeaders())
}

@CName(externName = "ZetaHttpClient_patch")
fun ZetaHttpClient_patch(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? = executeRequest(httpClient, httpRequest) { client, req ->
    client.delegate.patch(req.url?.toKString() ?: "", req.parseHeaders())
}

@CName(externName = "ZetaHttpClient_options")
fun ZetaHttpClient_options(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? = executeRequest(httpClient, httpRequest) { client, req ->
    client.delegate.options(req.url?.toKString() ?: "", req.parseHeaders())
}

@CName(externName = "ZetaHttpClient_head")
fun ZetaHttpClient_head(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? = executeRequest(httpClient, httpRequest) { client, req ->
    client.delegate.head(req.url?.toKString() ?: "", req.parseHeaders())
}

@CName(externName = "ZetaHttpClient_delete")
fun ZetaHttpClient_delete(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? = executeRequest(httpClient, httpRequest) { client, req ->
    client.delegate.delete(req.url?.toKString() ?: "", req.parseHeaders())
}

@CName(externName = "ZetaHttpClient_getAsync")
fun ZetaHttpClient_getAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
) = executeRequestAsync(httpClient, httpRequest, onSuccess, onError) { client, url, _, headers ->
    client.delegate.get(url, headers)
}

@CName(externName = "ZetaHttpClient_postAsync")
fun ZetaHttpClient_postAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
) = executeRequestAsync(httpClient, httpRequest, onSuccess, onError) { client, url, body, headers ->
    client.delegate.post(url, body, headers)
}

@CName(externName = "ZetaHttpClient_putAsync")
fun ZetaHttpClient_putAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
) = executeRequestAsync(httpClient, httpRequest, onSuccess, onError) { client, url, body, headers ->
    client.delegate.put(url, body, headers)
}

@CName(externName = "ZetaHttpClient_patchAsync")
fun ZetaHttpClient_patchAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
) = executeRequestAsync(httpClient, httpRequest, onSuccess, onError) { client, url, body, headers ->
    client.delegate.patch(url, body, headers)
}

@CName(externName = "ZetaHttpClient_deleteAsync")
fun ZetaHttpClient_deleteAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
) = executeRequestAsync(httpClient, httpRequest, onSuccess, onError) { client, url, _, headers ->
    client.delegate.delete(url, headers)
}

@CName(externName = "ZetaHttpClient_optionsAsync")
fun ZetaHttpClient_optionsAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
) = executeRequestAsync(httpClient, httpRequest, onSuccess, onError) { client, url, _, headers ->
    client.delegate.options(url, headers)
}

@CName(externName = "ZetaHttpClient_headAsync")
fun ZetaHttpClient_headAsync(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
    onSuccess: CPointer<CFunction<(CPointer<ZetaSdk_HttpResponse>?) -> Unit>>,
    onError: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>,
) = executeRequestAsync(httpClient, httpRequest, onSuccess, onError) { client, url, _, headers ->
    client.delegate.head(url, headers)
}
