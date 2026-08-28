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
@file:Suppress("FunctionNaming", "NoNameShadowing", "ReplaceGetOrSet")
// ReplaceGetOrSet is suppressed globally in this file because StableRef<>.get()
// cannot be replaced with the [] operator as it is not available on StableRef
package de.gematik.zeta.sdk

import de.gematik.zeta.logging.Log
import de.gematik.zeta.logging.ZetaLogLevel
import de.gematik.zeta.logging.ZetaLogger
import de.gematik.zeta.platform.Platform
import de.gematik.zeta.platform.platform
import de.gematik.zeta.sdk.ZetaSdk.clearRegistration
import de.gematik.zeta.sdk.attestation.model.PlatformProductId
import de.gematik.zeta.sdk.authentication.AuthConfig
import de.gematik.zeta.sdk.authentication.smb.SmbTokenProvider
import de.gematik.zeta.sdk.authentication.smcb.CustomConnectorApi
import de.gematik.zeta.sdk.authentication.smcb.CustomSmcbTokenProvider
import de.gematik.zeta.sdk.network.http.client.ZetaHttpClientBuilder
import de.gematik.zeta.sdk.network.http.client.config.NetworkConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyConfig
import de.gematik.zeta.sdk.network.http.client.config.ProxyType
import de.gematik.zeta.sdk.storage.SdkStorage
import de.gematik.zeta.sdk.storage.StorageConfig
import interop.ZETA_LOG_LEVEL_DEBUG
import interop.ZETA_LOG_LEVEL_INFO
import interop.ZETA_LOG_LEVEL_NONE
import interop.ZETA_LOG_LEVEL_WARN
import interop.ZetaSdk_BuildConfig
import interop.ZetaSdk_Client
import interop.ZetaSdk_HttpClient
import interop.ZetaSdk_HttpHeader
import interop.ZetaSdk_HttpResponse
import interop.ZetaSdk_StorageVTable
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.free
import platform.posix.strdup
import kotlin.collections.emptyList
import kotlin.collections.orEmpty
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal

data class NativeHttpSecurityConfig(
    val additionalCaPem: List<String> = emptyList(),
    val additionalCaFile: String? = null,
    val disableServerValidation: Boolean = false,
    val sslVerbose: Boolean = false,
    val proxyConfig: ProxyConfig? = null,
    val revocationCacheDurationSeconds: Long? = null,
)
var globalHttpSecurityConfig = NativeHttpSecurityConfig()
var globalNetworkConfig: NetworkConfig? = null

@ThreadLocal
private var lastErrorMessage: String? = null

private fun setLastError(e: Throwable) {
    lastErrorMessage = e.message ?: e.toString()
    Log.e { "[SDK-EXCEPTION] ${e::class.simpleName}: $lastErrorMessage" }
}

internal inline fun <T> guardExportedFunction(errorValue: T, block: () -> T): T =
    try {
        block()
    } catch (e: Throwable) {
        setLastError(e)
        errorValue
    }

@CName(externName = "ZetaSdk_getLastError")
fun ZetaSdk_getLastError(): CPointer<ByteVar>? =
    lastErrorMessage?.let { strdup(it) }

@CName(externName = "ZetaSdk_freeLastError")
fun ZetaSdk_freeLastError(ptr: CPointer<ByteVar>?): Unit = guardExportedFunction(Unit) {
    ptr?.let { free(it) }
}

@CName(externName = "ZetaSdk_getVersion")
fun ZetaSdk_getVersion(): CPointer<ByteVar>? = guardExportedFunction(errorValue = null) {
    strdup(ZetaSdk.getVersion())
}

@CName(externName = "ZetaSdk_freeVersion")
fun ZetaSdk_freeVersion(ptr: CPointer<ByteVar>?): Unit = guardExportedFunction(Unit) {
    ptr?.let { free(it) }
}

@CName(externName = "ZetaSdk_buildZetaClient")
fun ZetaSdk_buildSdkClient(
    buildConfig: CPointer<ZetaSdk_BuildConfig>,
): CPointer<ZetaSdk_Client>? = guardExportedFunction(errorValue = null) {
    val cBuildConfig = buildConfig.pointed
    val cStorageConfig = cBuildConfig.storageConfig!!.pointed
    val cAuthConfig = cBuildConfig.authConfig!!.pointed
    val cSmbConfig = cAuthConfig.smbConfig?.pointed
    val cSmcbConfig = cAuthConfig.smcbConfig?.pointed
    val cSecurityConfig = cBuildConfig.securityConfig?.pointed
    val cNetworkConfig = cBuildConfig.networkConfig?.pointed

    val additionalCaPem = cSecurityConfig
        ?.additionalCaPem
        ?.let { ptr ->
            (0 until cSecurityConfig.additionalCaPemCount)
                .mapNotNull { index ->
                    ptr[index]?.toKString()
                }
        }
        ?: emptyList()

    val additionalCaFile = cSecurityConfig
        ?.additionalCaFile
        ?.toKString()

    val disableServerValidation = cSecurityConfig
        ?.disableServerValidation
        ?: false

    val sslVerbose = cSecurityConfig
        ?.sslVerbose
        ?: false

    globalNetworkConfig = cNetworkConfig?.let {
        NetworkConfig(
            connectionTimeoutMillis = it.connectTimeoutMillis.takeIf { v -> v > 0 } ?: NetworkConfig().connectionTimeoutMillis,
            requestTimeoutMillis = it.requestTimeoutMillis.takeIf { v -> v > 0 } ?: NetworkConfig().requestTimeoutMillis,
            socketTimeoutMillis = it.socketTimeoutMillis.takeIf { v -> v > 0 } ?: NetworkConfig().socketTimeoutMillis,
            maxRetries = it.maxRetries,
            retryOnlyIdempotent = it.retryOnlyIdempotent,
        )
    }

    val revocationCacheDurationSeconds = cSecurityConfig
        ?.revocationCacheDurationSeconds
        ?.takeIf { it > 0 }

    globalHttpSecurityConfig = NativeHttpSecurityConfig(
        additionalCaPem = additionalCaPem,
        additionalCaFile = additionalCaFile,
        disableServerValidation = disableServerValidation,
        sslVerbose = sslVerbose,
        revocationCacheDurationSeconds = revocationCacheDurationSeconds,
        proxyConfig = cBuildConfig.proxyConfig?.pointed?.let {
            ProxyConfig(
                type = if (it.type == 1) ProxyType.SOCKS else ProxyType.HTTP,
                host = it.host?.toKString() ?: "",
                port = it.port,
                username = it.username?.toKString(),
                password = it.password?.toKString()?.toCharArray(),
            )
        },
    )

    val storageConfig = buildStorageConfig(cStorageConfig)
    setupLogger(cBuildConfig)
    val authConfig = buildAuthConfig(cAuthConfig, cSmbConfig, cSmcbConfig)

    val zetaSdkClient = ZetaSdk.build(
        cBuildConfig.resource?.toKString() ?: "",
        BuildConfig(
            cBuildConfig.productId?.toKString() ?: "",
            cBuildConfig.productVersion?.toKString() ?: "",
            cBuildConfig.clientName?.toKString() ?: "",
            storageConfig,
            object : TpmConfig {},
            authConfig,
            platformProductId = getPlatformProduct(),
            ZetaHttpClientBuilder()
                .disableServerValidation(globalHttpSecurityConfig.disableServerValidation)
                .apply {
                    globalHttpSecurityConfig.additionalCaPem.forEach { pem ->
                        addCaPem(pem)
                    }

                    globalHttpSecurityConfig.additionalCaFile?.let { file ->
                        addCaPemFile(file)
                    }

                    globalHttpSecurityConfig.revocationCacheDurationSeconds?.let { seconds ->
                        revocationCacheDuration(seconds)
                    }

                    globalHttpSecurityConfig.proxyConfig?.let { proxy(it) }
                    globalNetworkConfig?.let { net ->
                        timeouts(
                            connectMs = net.connectionTimeoutMillis,
                            requestMs = net.requestTimeoutMillis,
                            socketMs = net.socketTimeoutMillis,
                        )
                        if (net.maxRetries > 0) {
                            retry(maxRetries = net.maxRetries, onlyIdempotent = net.retryOnlyIdempotent)
                        }
                    }
                }
                .contentNegotiation(true)
                .logging(LogLevel.ALL),

        ),
    )

    nativeHeap.alloc<ZetaSdk_Client>().let { sdkClient ->
        sdkClient.zetaSdkClient = StableRef.create(zetaSdkClient).asCPointer()
        sdkClient.ptr
    }
}

private fun buildStorageConfig(cStorageConfig: interop.ZetaSdk_StorageConfig): StorageConfig {
    val vtablePtr = cStorageConfig.customStorage
    return if (vtablePtr != null) {
        StorageConfig.Custom(provider = vtablePtr.pointed.toSdkStorage())
    } else {
        val aesB64Key = cStorageConfig.aesB64Key?.toKString() ?: error("aesB64Key is required")
        StorageConfig.Default(
            aesB64Key = aesB64Key,
            linuxFilePath = cStorageConfig.storagePath?.toKString(),
        )
    }
}

private fun setupLogger(cBuildConfig: interop.ZetaSdk_BuildConfig) {
    cBuildConfig.logVTable?.let { vtable ->
        val level = when (vtable.pointed.logLevel) {
            ZETA_LOG_LEVEL_DEBUG -> ZetaLogLevel.DEBUG
            ZETA_LOG_LEVEL_INFO -> ZetaLogLevel.INFO
            ZETA_LOG_LEVEL_WARN -> ZetaLogLevel.WARN
            ZETA_LOG_LEVEL_NONE -> ZetaLogLevel.NONE
            else -> ZetaLogLevel.ERROR
        }
        Log.setLogLevel(level)
        Log.setLogger(object : ZetaLogger {
            override fun i(tag: String?, message: () -> String, throwable: Throwable?) {
                memScoped {
                    vtable.pointed.log?.invoke(vtable.pointed.context, "INFO".cstr.ptr, tag?.cstr?.ptr, message().cstr.ptr)
                }
            }
            override fun w(tag: String?, message: () -> String, throwable: Throwable?) {
                memScoped {
                    vtable.pointed.log?.invoke(vtable.pointed.context, "WARN".cstr.ptr, tag?.cstr?.ptr, message().cstr.ptr)
                }
            }
            override fun e(tag: String?, message: () -> String, throwable: Throwable?) {
                memScoped {
                    vtable.pointed.log?.invoke(vtable.pointed.context, "ERROR".cstr.ptr, tag?.cstr?.ptr, message().cstr.ptr)
                }
            }
            override fun d(tag: String?, message: () -> String, throwable: Throwable?) {
                memScoped {
                    vtable.pointed.log?.invoke(vtable.pointed.context, "DEBUG".cstr.ptr, tag?.cstr?.ptr, message().cstr.ptr)
                }
            }
        })
    }
}

private fun buildAuthConfig(
    cAuthConfig: interop.ZetaSdk_AuthConfig,
    cSmbConfig: interop.ZetaSdk_SmbConfig?,
    cSmcbConfig: interop.ZetaSdk_SmcbConfig?,
): AuthConfig {
    val subjectTokenProvider = buildSubjectTokenProvider(cSmbConfig, cSmcbConfig)
    return AuthConfig(
        scopes = cAuthConfig.scopes?.toKList(cAuthConfig.scopesCount)?.filterNotNull().orEmpty(),
        exp = cAuthConfig.exp,
        aslProdEnvironment = cAuthConfig.aslProdEnvironment,
        subjectTokenProvider = subjectTokenProvider,
        requiredRoleOid = cAuthConfig.requiredOid?.toKString() ?: "",
    )
}

private fun buildSubjectTokenProvider(
    cSmbConfig: interop.ZetaSdk_SmbConfig?,
    cSmcbConfig: interop.ZetaSdk_SmcbConfig?,
): de.gematik.zeta.sdk.authentication.SubjectTokenProvider {
    val keystoreFile = cSmbConfig?.keystoreFile?.toKString() ?: ""

    return when {
        keystoreFile.isNotEmpty() -> SmbTokenProvider(
            SmbTokenProvider.Credentials(
                keystoreFile,
                cSmbConfig?.alias?.toKString() ?: "",
                cSmbConfig?.password?.toKString() ?: "",
            ),
        )
        cSmcbConfig?.customSmcb != null -> buildCustomSmcbProvider(cSmcbConfig.customSmcb!!.pointed)
        else -> error("Should specify SM-B / SMC-B subject token provider")
    }
}

private fun buildCustomSmcbProvider(vtable: interop.ZetaSdk_SmcbVTable): CustomSmcbTokenProvider =
    CustomSmcbTokenProvider(
        connectorApi = object : CustomConnectorApi {
            override suspend fun readCertificate(): ByteArray {
                return suspendCancellableCoroutine { cont ->
                    val cb = staticCFunction { cbCtx: COpaquePointer?, data: CPointer<UByteVar>?, size: Int ->
                        val ref = cbCtx?.asStableRef<Continuation<ByteArray>>() ?: return@staticCFunction
                        val bytes = data?.readBytes(size) ?: ByteArray(0)
                        ref.get().resume(bytes)
                        ref.dispose()
                    }
                    val readCertFn = vtable.readCertificate
                    if (readCertFn == null) {
                        cont.cancel(IllegalStateException("readCertificate function pointer is null"))
                        return@suspendCancellableCoroutine
                    }
                    val ref = StableRef.create(cont)
                    readCertFn(vtable.context, cb, ref.asCPointer())
                }
            }

            override suspend fun externalAuthenticate(base64Challenge: String): ByteArray {
                return suspendCancellableCoroutine { cont ->
                    val cb = staticCFunction { cbCtx: COpaquePointer?, data: CPointer<UByteVar>?, size: Int ->
                        val ref = cbCtx?.asStableRef<Continuation<ByteArray>>() ?: return@staticCFunction
                        val bytes = data?.readBytes(size) ?: ByteArray(0)
                        ref.get().resume(bytes)
                        ref.dispose()
                    }
                    val authFn = vtable.externalAuthenticate
                    if (authFn == null) {
                        cont.cancel(IllegalStateException("externalAuthenticate function pointer is null"))
                        return@suspendCancellableCoroutine
                    }
                    val ref = StableRef.create(cont)
                    memScoped {
                        authFn(vtable.context, base64Challenge.cstr.ptr, cb, ref.asCPointer())
                    }
                }
            }
        },
    )

@CName(externName = "ZetaSdk_clearZetaClient")
fun ZetaSdk_clearZetaClient(
    sdkClient: CPointer<ZetaSdk_Client>,
): Unit = guardExportedFunction(errorValue = Unit) {
    sdkClient.pointed.let { sdkClient ->
        sdkClient.zetaSdkClient!!.asStableRef<ZetaSdkClient>().dispose()
    }
    nativeHeap.free(sdkClient.rawValue)
}

@CName(externName = "ZetaSdk_buildHttpClient")
fun ZetaSdk_buildHttpClient(
    sdkClient: CPointer<ZetaSdk_Client>,
): CPointer<ZetaSdk_HttpClient>? = guardExportedFunction(errorValue = null) {
    // asStableRef<>().get() cannot be replaced with [] operator as it is not available on StableRef
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    val zetaHttpClient = zetaSdkClient.httpClient {
        logging(LogLevel.ALL)
        disableServerValidation(globalHttpSecurityConfig.disableServerValidation)
        globalHttpSecurityConfig.additionalCaPem.forEach { pem ->
            addCaPem(pem)
        }
        globalHttpSecurityConfig.additionalCaFile?.let { file ->
            addCaPemFile(file)
        }
        contentNegotiation(true)
        globalHttpSecurityConfig.proxyConfig?.let { proxy(it) }

        globalHttpSecurityConfig.revocationCacheDurationSeconds?.let { seconds ->
            revocationCacheDuration(seconds)
        }
        globalNetworkConfig?.let { net ->
            timeouts(
                connectMs = net.connectionTimeoutMillis,
                requestMs = net.requestTimeoutMillis,
                socketMs = net.socketTimeoutMillis,
            )
            if (net.maxRetries > 0) {
                retry(maxRetries = net.maxRetries, onlyIdempotent = net.retryOnlyIdempotent)
            }
        }
    }
    nativeHeap.alloc<ZetaSdk_HttpClient>().let { httpClient ->
        httpClient.zetaHttpClient = StableRef.create(zetaHttpClient).asCPointer()
        httpClient.ptr
    }
}

@CName(externName = "ZetaSdk_clearHttpClient")
fun ZetaSdk_clearHttpClient(
    httpClient: CPointer<ZetaSdk_HttpClient>,
): Unit = guardExportedFunction(errorValue = Unit) {
    httpClient.pointed.let { sdkClient ->
        sdkClient.zetaHttpClient!!.asStableRef<ZetaSdkClient>().dispose()
    }
    nativeHeap.free(httpClient.rawValue)
}

@CName(externName = "ZetaHttpResponse_destroy")
fun ZetaHttpResponse_destroy(
    httpResponse: CPointer<ZetaSdk_HttpResponse>,
): Unit = guardExportedFunction(errorValue = Unit) {
    httpResponse.pointed.let { httpResponse ->
        httpResponse.body?.let { free(it) }
        httpResponse.error?.let { free(it) }
        httpResponse.headers?.toKList(httpResponse.headersCount)?.forEach {
            it?.key?.let { free(it) }
            it?.value?.let { free(it) }
        }
        httpResponse.headers?.let { nativeHeap.free(it) }
    }
    nativeHeap.free(httpResponse.rawValue)
}

@CName(externName = "ZetaSdk_close")
fun ZetaSdk_close(sdkClient: CPointer<ZetaSdk_Client>): Int = guardExportedFunction(errorValue = -1) {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    runBlocking {
        zetaSdkClient.close().fold(
            onSuccess = { 0 },
            onFailure = {
                Log.e { "[SDK] close failed: ${it.message}" }
                -1
            },
        )
    }
}

@CName(externName = "ZetaSdk_logout")
fun ZetaSdk_logout(sdkClient: CPointer<ZetaSdk_Client>): Int = guardExportedFunction(errorValue = -1) {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    runBlocking {
        zetaSdkClient.logout().fold(
            onSuccess = { 0 },
            onFailure = {
                Log.e { "[SDK] logout failed: ${it.message}" }
                -1
            },
        )
    }
}

@CName(externName = "ZetaSdk_clearRegistration")
fun ZetaSdk_clearRegistration(sdkClient: CPointer<ZetaSdk_Client>): Int = guardExportedFunction(errorValue = -1) {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    runBlocking {
        zetaSdkClient.clearRegistration().fold(
            onSuccess = { 0 },
            onFailure = {
                Log.e { "[SDK] clear registration failed: ${it.message}" }
                -1
            },
        )
    }
}

@CName(externName = "ZetaSdk_discover")
fun ZetaSdk_discover(sdkClient: CPointer<ZetaSdk_Client>): Int = guardExportedFunction(errorValue = -1) {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    runBlocking {
        zetaSdkClient.discover().fold(
            onSuccess = { 0 },
            onFailure = {
                Log.e { "[SDK] discover failed: ${it.message}" }
                -1
            },
        )
    }
}

@CName(externName = "ZetaSdk_register")
fun ZetaSdk_register(sdkClient: CPointer<ZetaSdk_Client>): Int = guardExportedFunction(errorValue = -1) {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    runBlocking {
        zetaSdkClient.register().fold(
            onSuccess = { 0 },
            onFailure = {
                Log.e { "[SDK] register failed: ${it.message}" }
                -1
            },
        )
    }
}

@CName(externName = "ZetaSdk_authenticate")
fun ZetaSdk_authenticate(sdkClient: CPointer<ZetaSdk_Client>): Int = guardExportedFunction(errorValue = -1) {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    runBlocking {
        zetaSdkClient.authenticate().fold(
            onSuccess = { 0 },
            onFailure = {
                Log.e { "[SDK] authenticate failed: ${it.message}" }
                -1
            },
        )
    }
}

@CName(externName = "ZetaSdk_status")
fun ZetaSdk_status(
    sdkClient: CPointer<ZetaSdk_Client>,
): Int = guardExportedFunction(errorValue = -1) {
    val zetaSdkClient = sdkClient.pointed.zetaSdkClient!!.asStableRef<ZetaSdkClient>().get()
    val status = runBlocking {
        zetaSdkClient.status().getOrNull()
    }
    when (status) {
        SdkStatus.NOT_REGISTERED -> 0
        SdkStatus.REGISTERED_NO_VALID_TOKENS -> 1
        SdkStatus.HAS_REFRESH_TOKEN -> 2
        SdkStatus.HAS_ACCESS_AND_REFRESH_TOKEN -> 3
        null -> -2
    }
}

fun CPointer<CPointerVar<ByteVar>>.toKList(count: Int): List<String?> {
    return List(count) { i -> this[i]?.toKString() }
}

fun CPointer<ZetaSdk_HttpHeader>.toKList(count: Int): List<ZetaSdk_HttpHeader?> {
    return List(count) { i -> this[i] }
}

private fun getPlatformProduct(): PlatformProductId {
    return when (val plat = platform()) {
        is Platform.Jvm.Macos, Platform.Native.Macos -> PlatformProductId.AppleProductId("apple", "macos", listOf())
        is Platform.Jvm.Linux, Platform.Native.Linux -> PlatformProductId.LinuxProductId("linux", "", "demo-client", "0.5.0")
        is Platform.Jvm.Windows, Platform.Native.Windows -> PlatformProductId.WindowsProductId("windows", "", "demo-client")
        else -> error("Unknown platform: $plat")
    }
}
private fun ZetaSdk_StorageVTable.toSdkStorage(): SdkStorage = object : SdkStorage {
    override suspend fun put(key: String, value: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val ref = StableRef.create(cont).asCPointer()
            memScoped {
                put!!(
                    context, key.cstr.getPointer(this), value.cstr.getPointer(this),
                    staticCFunction { cbCtx ->
                        val c = cbCtx!!.asStableRef<CancellableContinuation<Unit>>()
                        c.get().resume(Unit) // NOSONAR false positive, no indexed accessor
                        c.dispose()
                    },
                    ref,
                )
            }
        }

    override suspend fun get(key: String): String? =
        suspendCancellableCoroutine { cont ->
            val ref = StableRef.create(cont).asCPointer()
            memScoped {
                get!!(
                    context, key.cstr.getPointer(this),
                    staticCFunction { cbCtx, value ->
                        val c = cbCtx!!.asStableRef<CancellableContinuation<String?>>()
                        c.get().resume(value?.toKString()) // NOSONAR false positive, no indexed accessor
                        c.dispose()
                    },
                    ref,
                )
            }
        }

    override suspend fun remove(key: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val ref = StableRef.create(cont).asCPointer()
            memScoped {
                remove!!(
                    context, key.cstr.getPointer(this),
                    staticCFunction { cbCtx ->
                        val c = cbCtx!!.asStableRef<CancellableContinuation<Unit>>()
                        c.get().resume(Unit) // NOSONAR false positive, no indexed accessor
                        c.dispose()
                    },
                    ref,
                )
            }
        }

    override suspend fun clear() =
        suspendCancellableCoroutine<Unit> { cont ->
            val ref = StableRef.create(cont).asCPointer()
            clear!!(
                context,
                staticCFunction { cbCtx ->
                    val c = cbCtx!!.asStableRef<CancellableContinuation<Unit>>()
                    c.get().resume(Unit) // NOSONAR false positive, no indexed accessor
                    c.dispose()
                },
                ref,
            )
        }
}
