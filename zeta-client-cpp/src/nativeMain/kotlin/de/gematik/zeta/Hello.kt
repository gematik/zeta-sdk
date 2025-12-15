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

@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)

import de.gematik.zeta.sdk.BuildConfig
import de.gematik.zeta.sdk.StorageConfig
import de.gematik.zeta.sdk.TpmConfig
import de.gematik.zeta.sdk.ZetaSdk
import de.gematik.zeta.sdk.ZetaSdkClient
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.SmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.HttpClientExtension.httpGet
import de.gematik.zeta.sdk.network.http.client.HttpClientExtension.httpPost
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClient
import de.gematik.zeta.sdk.storage.InMemoryStorage
import interop.ZetaSdk_BuildConfig
import interop.ZetaSdk_Client
import interop.ZetaSdk_HttpClient
import interop.ZetaSdk_HttpHeader
import interop.ZetaSdk_HttpRequest
import interop.ZetaSdk_HttpResponse
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.strdup
import kotlin.experimental.ExperimentalNativeApi

@CName(externName = "ZetaSdk_buildZetaClient")
fun ZetaSdk_buildSdkClient(
    buildConfig: CPointer<ZetaSdk_BuildConfig>,
): CPointer<ZetaSdk_Client> {
    val cBuildConfig = buildConfig.pointed
    val cStorageConfig = cBuildConfig.storageConfig!!.pointed
    val cTpmConfig = cBuildConfig.tpmConfig!!.pointed
    val cAuthConfig = cBuildConfig.authConfig!!.pointed
    val cSmbConfig = cAuthConfig.smbConfig?.pointed
    val cSmcbConfig = cAuthConfig.smcbConfig?.pointed

    val storageConfig = cStorageConfig.let { storageConfig ->
        StorageConfig(InMemoryStorage())
    }
    val tpmConfig = cTpmConfig.let { tpmConfig ->
        object : TpmConfig {}
    }
    val authConfig = cAuthConfig.let { authConfig ->
        val keystoreFile = cSmbConfig?.keystoreFile?.toKString() ?: ""
        val baseUrl = cSmcbConfig?.baseUrl?.toKString() ?: ""

        val subjectTokenProvider = when {
            keystoreFile.isNotEmpty() -> SmbTokenProvider(
                SmbTokenProvider.Credentials(
                    keystoreFile,
                    cSmbConfig?.alias?.toKString() ?: "",
                    cSmbConfig?.password?.toKString() ?: "",
                )
            )
            baseUrl.isNotEmpty() -> SmcbTokenProvider(
                SmcbTokenProvider.ConnectorConfig(
                    baseUrl,
                    cSmcbConfig?.mandantId?.toKString() ?: "",
                    cSmcbConfig?.clientSystemId?.toKString() ?: "",
                    cSmcbConfig?.workspaceId?.toKString() ?: "",
                    cSmcbConfig?.userId?.toKString() ?: "",
                    cSmcbConfig?.cardHandle?.toKString() ?: "",
                )
            )
            else -> throw Exception("Should specify SM-B / SMC-B subject token provider")
        }
        AuthConfig(
            authConfig.scopes?.toKList(authConfig.scopesCount)?.filterNotNull().orEmpty(),
            authConfig.exp,
            authConfig.enableAslTracingHeader,
            subjectTokenProvider,
        )
    }
    val zetaSdkClient = cBuildConfig.let { buildConfig ->
        ZetaSdk.build(
            buildConfig.resource?.toKString() ?: "",
            BuildConfig(
                buildConfig.productId?.toKString() ?: "",
                buildConfig.productVersion?.toKString() ?: "",
                buildConfig.clientName?.toKString() ?: "",
                storageConfig!!,
                tpmConfig!!,
                authConfig!!,
            )
        )
    }
    return nativeHeap.alloc<ZetaSdk_Client>().let { sdkClient ->
        sdkClient.zetaSdkClient = StableRef.create(zetaSdkClient).asCPointer()
        sdkClient.ptr
    }
}

@CName(externName = "ZetaSdk_clearZetaClient")
fun ZetaSdk_clearZetaClient(
    sdkClient: CPointer<ZetaSdk_Client>
) {
    sdkClient.pointed.let { sdkClient ->
        sdkClient.zetaSdkClient!!.asStableRef<ZetaSdkClient>().dispose()
    }
    nativeHeap.free(sdkClient.rawValue)
}

@CName(externName = "ZetaSdk_buildHttpClient")
fun ZetaSdk_buildHttpClient(
    sdkClient: CPointer<ZetaSdk_Client>
): CPointer<ZetaSdk_HttpClient> {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    val logger = object : Logger {
        override fun log(message: String) {
            println("CPP Log :$message")
        }
    }
    val zetaHttpClient = zetaSdkClient.httpClient {
        logging(LogLevel.ALL, logger)
    }
    return nativeHeap.alloc<ZetaSdk_HttpClient>().let { httpClient ->
        httpClient.zetaHttpClient = StableRef.create(zetaHttpClient).asCPointer()
        httpClient.ptr
    }
}

@CName(externName = "ZetaSdk_clearHttpClient")
fun ZetaSdk_clearHttpClient(
    httpClient: CPointer<ZetaSdk_HttpClient>
) {
    httpClient.pointed.let { sdkClient ->
        sdkClient.zetaHttpClient!!.asStableRef<ZetaSdkClient>().dispose()
    }
    nativeHeap.free(httpClient.rawValue)
}

@CName(externName = "ZetaHttpClient_httpGet")
fun ZetaHttpClient_httpGet(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? {
    val zetaHttpClient = httpClient.pointed.zetaHttpClient!!.asStableRef<ZetaHttpClient>().get()
    val zetaHttpRequest = httpRequest.pointed

    return try {
        val url = zetaHttpRequest.url?.toKString() ?: ""
        val headers = zetaHttpRequest.headers
            ?.toKList(zetaHttpRequest.headersCount)
            ?.associate {
                val key = it?.key?.toKString() ?: ""
                val value = it?.value?.toKString() ?: ""
                key to value
            } ?: emptyMap()
        val result = zetaHttpClient.delegate.httpGet(url, headers)
        val resultHeaders = nativeHeap.allocArray<ZetaSdk_HttpHeader>(result.headers.size)
        result.headers.entries.forEachIndexed { index, header ->
            resultHeaders[index].key = strdup(header.key)!!
            resultHeaders[index].value = strdup(header.value)!!
        }
        nativeHeap.alloc<ZetaSdk_HttpResponse>().let { httpResponse ->
            httpResponse.status = result.status
            httpResponse.body = strdup(result.body)!!
            httpResponse.headers = resultHeaders
            httpResponse.error = null
            httpResponse.ptr
        }
    } catch (e: Exception) {
        e.printStackTrace()
        nativeHeap.alloc<ZetaSdk_HttpResponse>().let { httpResponse ->
            httpResponse.body = null
            httpResponse.error = strdup(e.message)!!
            httpResponse.ptr
        }
    }
}

@CName(externName = "ZetaHttpClient_httpPost")
fun ZetaHttpClient_httpPost(
    httpClient: CPointer<ZetaSdk_HttpClient>,
    httpRequest: CPointer<ZetaSdk_HttpRequest>,
): CPointer<ZetaSdk_HttpResponse>? {
    val zetaHttpClient = httpClient.pointed.zetaHttpClient!!.asStableRef<ZetaHttpClient>().get()
    val zetaHttpRequest = httpRequest.pointed

    return try {
        val url = zetaHttpRequest.url?.toKString() ?: ""
        val body = zetaHttpRequest.body?.toKString() ?: ""
        val headers = zetaHttpRequest.headers
            ?.toKList(zetaHttpRequest.headersCount)
            ?.associate {
                val key = it?.key?.toKString() ?: ""
                val value = it?.value?.toKString() ?: ""
                key to value
            } ?: emptyMap()
        val result = zetaHttpClient.delegate.httpPost(url, body, headers)
        val resultHeaders = nativeHeap.allocArray<ZetaSdk_HttpHeader>(result.headers.size)
        result.headers.entries.forEachIndexed { index, header ->
            resultHeaders[index].key = strdup(header.key)!!
            resultHeaders[index].value = strdup(header.value)!!
        }
        nativeHeap.alloc<ZetaSdk_HttpResponse>().let { httpResponse ->
            httpResponse.status = result.status
            httpResponse.body = strdup(result.body)!!
            httpResponse.headers = resultHeaders
            httpResponse.error = null
            httpResponse.ptr
        }
    } catch (e: Exception) {
        e.printStackTrace()
        nativeHeap.alloc<ZetaSdk_HttpResponse>().let { httpResponse ->
            httpResponse.body = null
            httpResponse.error = strdup(e.message)!!
            httpResponse.ptr
        }
    }
}

@CName(externName = "ZetaHttpResponse_destroy")
fun ZetaHttpResponse_destroy(
    httpResponse: CPointer<ZetaSdk_HttpResponse>,
) {
    httpResponse.pointed.let { httpResponse ->
        httpResponse.body?.let { nativeHeap.free(it.rawValue)  }
        httpResponse.error?.let { nativeHeap.free(it.rawValue)  }
    }
    nativeHeap.free(httpResponse.rawValue)
}

fun CPointer<CPointerVar<ByteVar>>.toKList(count: Int): List<String?> {
    return List(count) { i -> this[i]?.toKString() }
}

fun CPointer<ZetaSdk_HttpHeader>.toKList(count: Int): List<ZetaSdk_HttpHeader?> {
    return List(count) { i -> this[i] }
}

