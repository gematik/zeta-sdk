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

using System.Runtime.InteropServices;
using System.Text;
using ZetaSdk.Config;
using ZetaSdk.Http;
using ZetaSdk.Native;
using ZetaSdk.WebSocket;

namespace ZetaSdk;

/// <summary>
/// Main entry point for the ZETA SDK. Wraps a native ZETA client instance and
/// exposes discovery, registration, authentication, HTTP, and WebSocket access
/// to ZETA-protected resources.
/// </summary>
public sealed class ZetaClient : IDisposable
{
    private          IntPtr _ptr;
    private          bool   _disposed;
    private CustomSmcbHandle? _customSmcbHandle;
    private CustomStorageHandle? _customStorageHandle;
    private CustomLogHandle? _customLogHandle;

    private ZetaClient(IntPtr ptr) => _ptr = ptr;

    /// <summary>
    /// Builds and initializes a new <see cref="ZetaClient"/> from the given configuration.
    /// </summary>
    /// <exception cref="ArgumentNullException"><paramref name="config"/> is <c>null</c>.</exception>
    /// <exception cref="ZetaSdkException">The native client could not be built.</exception>
    public static ZetaClient Build(ZetaClientConfig config)
    {
        ArgumentNullException.ThrowIfNull(config);
        var instance = new ZetaClient(IntPtr.Zero);

        using var mem       = new NativeMem();
        var       buildCfg  = instance.BuildNativeConfig(config, mem);
        var       ptr       = ZetaSdkNative.ZetaSdk_buildZetaClient(buildCfg);

        if (ptr == IntPtr.Zero)
        {
            instance.FreeNativeHandles();
            throw new ZetaSdkException("ZetaSdk_buildZetaClient returned null. Check your configuration.");
        }

        instance._ptr = ptr;
        return instance;
    }

    /// <summary>Creates a synchronous HTTP client authenticated for this ZETA session.</summary>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    /// <exception cref="ZetaSdkException">The native HTTP client could not be created.</exception>
    public ZetaHttpClient CreateHttpClient()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        var ptr = ZetaSdkNative.ZetaSdk_buildHttpClient(_ptr);
        if (ptr == IntPtr.Zero)
            throw new ZetaSdkException("ZetaSdk_buildHttpClient returned null.");
        return new ZetaHttpClient(ptr);
    }

    /// <summary>Creates an asynchronous HTTP client authenticated for this ZETA session.</summary>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    /// <exception cref="ZetaSdkException">The native HTTP client could not be created.</exception>
    public ZetaHttpClientAsync CreateHttpClientAsync()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        var ptr = ZetaSdkNative.ZetaSdk_buildHttpClient(_ptr);
        if (ptr == IntPtr.Zero)
            throw new ZetaSdkException("Failed to create HTTP client.");
        return new ZetaHttpClientAsync(ptr);
    }

    /// <summary>Returns the current registration/token status of this client.</summary>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    public ZetaSdkStatus GetStatus()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        var result = ZetaSdkNative.ZetaSdk_status(_ptr);
        return result switch
        {
            0  => ZetaSdkStatus.NotRegistered,
            1  => ZetaSdkStatus.RegisteredNoValidTokens,
            2  => ZetaSdkStatus.HasRefreshToken,
            3  => ZetaSdkStatus.HasAccessAndRefreshToken,
            _  => ZetaSdkStatus.Unknown
        };
    }

    /// <summary>Clears the current session's access and refresh tokens. Client registration is preserved.</summary>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    public void Logout()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        ZetaSdkNative.ZetaSdk_logout(_ptr);
    }

    /// <summary>Runs OAuth/ASL service discovery against the configured resource server.</summary>
    /// <returns><c>0</c> on success; a non-zero error code otherwise (see <see cref="GetLastError"/>).</returns>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    public int Discover()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return ZetaSdkNative.ZetaSdk_discover(_ptr);
    }

    /// <summary>Performs dynamic client registration against the authorization server, if not already registered.</summary>
    /// <returns><c>0</c> on success; a non-zero error code otherwise (see <see cref="GetLastError"/>).</returns>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    public int Register()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return ZetaSdkNative.ZetaSdk_register(_ptr);
    }

    /// <summary>Obtains a DPoP-bound access token for the configured resource and scopes.</summary>
    /// <returns><c>0</c> on success; a non-zero error code otherwise (see <see cref="GetLastError"/>).</returns>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    public int Authenticate()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return ZetaSdkNative.ZetaSdk_authenticate(_ptr);
    }

    /// <summary>Clears the client's registration, forcing a new <see cref="Register"/> call on next use.</summary>
    /// <returns><c>0</c> on success; a non-zero error code otherwise (see <see cref="GetLastError"/>).</returns>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    public int ClearRegistration()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return ZetaSdkNative.ZetaSdk_clearRegistration(_ptr);
    }

    /// <summary>Opens a WebSocket (STOMP) session to <paramref name="url"/> through this ZETA-authenticated connection.</summary>
    /// <param name="url">The WebSocket URL to connect to.</param>
    /// <param name="headers">Optional additional headers for the WebSocket upgrade request.</param>
    /// <param name="handler">Callback invoked with the opened <see cref="WsSession"/>.</param>
    /// <returns><c>0</c> on success; <c>-1</c> otherwise (see <see cref="GetLastError"/>).</returns>
    /// <exception cref="ObjectDisposedException">The client has already been disposed.</exception>
    /// <exception cref="ArgumentNullException"><paramref name="handler"/> is <c>null</c>.</exception>
    public int OpenWebSocket(
        string url,
        IReadOnlyDictionary<string, string>? headers,
        Action<WsSession> handler)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        ArgumentNullException.ThrowIfNull(handler);
        using var mem = new NativeMem();

        WsSessionHandlerDelegate nativeDelegate = wsSessionPtr =>
            handler(new WsSession(wsSessionPtr));

        var handlerPtr = Marshal.GetFunctionPointerForDelegate(nativeDelegate);
        var urlPtr = mem.Str(url);
        var urlBytes = Encoding.UTF8.GetBytes(url);
        var (hdrPtr, hdrLen) = headers is { Count: > 0 }
            ? mem.HeaderArray(headers)
            : (IntPtr.Zero, 0);

        ZetaSdkNative.ZetaSdk_Client_ws(_ptr, urlPtr, urlBytes.Length, handlerPtr, hdrPtr, hdrLen);
        GC.KeepAlive(nativeDelegate);

        return GetLastError() == null ? 0 : -1;
    }

    /// <summary>Returns and clears the most recent error message set by the native SDK, if any.</summary>
    public static string? GetLastError()
    {
        var ptr = ZetaSdkNative.ZetaSdk_getLastError();
        if (ptr == IntPtr.Zero) return null;
        var message = Marshal.PtrToStringUTF8(ptr);
        ZetaSdkNative.ZetaSdk_freeLastError(ptr);
        return message;
    }

    /// <summary>Returns the version of the native ZETA SDK linked into this process.</summary>
    public static string GetVersion()
    {
        var ptr = ZetaSdkNative.ZetaSdk_getVersion();
        if (ptr == IntPtr.Zero) return "";
        var version = Marshal.PtrToStringUTF8(ptr) ?? "";
        ZetaSdkNative.ZetaSdk_freeVersion(ptr);
        return version;
    }

    /// <summary>Releases the underlying native client and its associated callback handles.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        ZetaSdkNative.ZetaSdk_clearZetaClient(_ptr);

        FreeNativeHandles();
    }

    private void FreeNativeHandles()
    {
        _customSmcbHandle?.Dispose();
        _customSmcbHandle = null;
        _customStorageHandle?.Free();
        _customStorageHandle = null;
        _customLogHandle?.Free();
        _customLogHandle = null;
    }

    private IntPtr BuildNativeConfig(ZetaClientConfig cfg, NativeMem mem)
    {
       var storageVTablePtr = IntPtr.Zero;
       if (cfg.Storage?.CustomStorage is { } customStorage)
       {
           _customStorageHandle = new CustomStorageHandle(customStorage);
           storageVTablePtr = _customStorageHandle.VTablePtr;
       }

       var logVTablePtr = IntPtr.Zero;
       if (cfg.Logger is { } logger)
       {
          _customLogHandle = new CustomLogHandle(logger, (int)cfg.LogLevel);
          logVTablePtr = _customLogHandle.VTablePtr;
       }

       var proxyPtr = IntPtr.Zero;
       if (cfg.Proxy is { } proxy)
       {
           proxyPtr = mem.Struct(new NativeProxyConfig
           {
               host     = mem.Str(proxy.Host),
               port     = proxy.Port,
               username = mem.Str(proxy.Username),
               password = mem.Str(proxy.Password),
               type     = (int)proxy.Type
           });
       }

       var storage = mem.Struct(new NativeStorageConfig
       {
           aesB64Key     = mem.Str(cfg.Storage?.AesB64Key),
           storagePath   = mem.Str(cfg.Storage?.StoragePath),
           customStorage = storageVTablePtr
       });

        var tpm     = mem.Struct(new NativeTpmConfig());

        var smbPtr = IntPtr.Zero;
        if (cfg.Auth.Smb is { } smb)
        {
            smbPtr = mem.Struct(new NativeSmbConfig
            {
                keystoreFile = mem.Str(smb.KeystoreFile),
                alias        = mem.Str(smb.Alias),
                password     = mem.Str(smb.Password)
            });
        }


        var (scopesPtr, scopesLen) = mem.StringArray(cfg.Auth.Scopes.ToArray());

        var smcbPtr = IntPtr.Zero;
        if (cfg.Auth.CustomSmcb is { } customConnector)
        {
            _customSmcbHandle = new CustomSmcbHandle(customConnector);
            smcbPtr = mem.Struct(new NativeSmcbConfig
            {
                customSmcb = _customSmcbHandle.VTablePtr
            });
        }

        var auth = mem.Struct(new NativeAuthConfig
        {
            scopes               = scopesPtr,
            scopesCount          = scopesLen,
            exp                  = cfg.Auth.ExpirySeconds,
            aslProdEnvironment   = cfg.Auth.AslProdEnvironment,
            smbConfig            = smbPtr,
            smcbConfig           = smcbPtr,
            requiredOid          = mem.Str(cfg.Auth.RequiredRoleOid)
        });


        var (caPemPtr, caPemLen) = mem.StringArray(
            cfg.Security?.AdditionalCaPem?.ToArray() ?? []
        );

        var securityPtr = mem.Struct(new NativeSecurityConfig
        {
            additionalCaPem = caPemPtr,
            additionalCaPemCount = caPemLen,
            additionalCaFile = mem.Str(cfg.Security?.AdditionalCaFile),
            disableServerValidation = cfg.Security?.DisableServerValidation ?? false,
            sslVerbose = cfg.Security?.SslVerbose ?? false,
            revocationCacheDurationSeconds = cfg.Security?.RevocationCacheDurationSeconds ?? 0,
        });

         var networkPtr = IntPtr.Zero;
         if (cfg.Network is { } network)
         {
             networkPtr = mem.Struct(new NativeNetworkConfig
             {
                 connectTimeoutMillis = network.ConnectTimeoutMillis,
                 requestTimeoutMillis = network.RequestTimeoutMillis,
                 socketTimeoutMillis  = network.SocketTimeoutMillis,
                 maxRetries           = network.MaxRetries,
                 retryOnlyIdempotent  = network.RetryOnlyIdempotent
             });
         }

        return mem.Struct(new NativeBuildConfig
        {
            resource       = mem.Str(cfg.Resource),
            productId      = mem.Str(cfg.ProductId),
            productVersion = mem.Str(cfg.ProductVersion),
            clientName     = mem.Str(cfg.ClientName),
            storageConfig  = storage,
            tpmConfig      = tpm,
            authConfig     = auth,
            logVTable      = logVTablePtr,
            proxyConfig    = proxyPtr,
            securityConfig = securityPtr,
            networkConfig  = networkPtr
        });
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void WsSessionHandlerDelegate(IntPtr wsSession);
}
