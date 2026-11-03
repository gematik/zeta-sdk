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

namespace ZetaSdk.Native;

[StructLayout(LayoutKind.Sequential)]
internal struct NativeHttpHeader { public IntPtr key; public IntPtr value; }

[StructLayout(LayoutKind.Sequential)]
internal struct NativeHttpRequest
{
    public IntPtr url;
    public IntPtr body;
    public IntPtr headers;
    public int    headersCount;
}

[StructLayout(LayoutKind.Sequential)]
internal struct NativeHttpResponse
{
    public int    status;
    public IntPtr body;
    public IntPtr headers;
    public int    headersCount;
    public IntPtr error;
}

[StructLayout(LayoutKind.Sequential)]
internal struct NativeSmbConfig
{
    public IntPtr keystoreFile;
    public IntPtr alias;
    public IntPtr password;
}

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
public delegate void NativeDataCallback(IntPtr cbCtx, IntPtr data, int size);

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
public delegate void NativeReadCertificateFn(IntPtr context, IntPtr callback, IntPtr cbCtx);

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
public delegate void NativeExternalAuthenticateFn(IntPtr context, IntPtr challenge, IntPtr callback, IntPtr cbCtx);

[StructLayout(LayoutKind.Sequential)]
internal struct NativeCustomSmcbVTable
{
    public IntPtr context;
    public IntPtr readCertificate;
    public IntPtr externalAuthenticate;
}

[StructLayout(LayoutKind.Sequential)]
internal struct NativeSmcbConfig
{
    public IntPtr customSmcb;
}

[StructLayout(LayoutKind.Sequential)]
internal struct NativeAuthConfig
{
    public IntPtr scopes;
    public int    scopesCount;
    public long   exp;
    [MarshalAs(UnmanagedType.I1)] public bool aslProdEnvironment;
    public IntPtr smbConfig;
    public IntPtr requiredOid;
    public IntPtr smcbConfig;
}

[StructLayout(LayoutKind.Sequential)] internal struct NativeTpmConfig     { }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeStorageConfig
{
    public IntPtr aesB64Key;
    public IntPtr storagePath;
    public IntPtr customStorage;
}

[StructLayout(LayoutKind.Sequential)]
internal struct NativeNetworkConfig
{
    public long connectTimeoutMillis;
    public long requestTimeoutMillis;
    public long socketTimeoutMillis;
    public int  maxRetries;
    [MarshalAs(UnmanagedType.I1)] public bool retryOnlyIdempotent;
}

[StructLayout(LayoutKind.Sequential)]
internal struct NativeBuildConfig
{
    public IntPtr resource;
    public IntPtr productId;
    public IntPtr productVersion;
    public IntPtr clientName;
    public IntPtr storageConfig;
    public IntPtr tpmConfig;
    public IntPtr authConfig;
    public IntPtr logVTable;
    public IntPtr proxyConfig;
    public IntPtr securityConfig;
    public IntPtr networkConfig;
}

[StructLayout(LayoutKind.Explicit)]
internal struct NativeWsMessage
{
    [FieldOffset(0)]  public int    type;
    [FieldOffset(8)]  public IntPtr textPtr;
    [FieldOffset(8)]  public IntPtr binaryDataPtr;
    [FieldOffset(16)] public int    binarySize;
}

internal enum NativeWsMessageType { Text = 0, Binary = 1, Close = 2 }

[StructLayout(LayoutKind.Sequential)]
internal struct NativeProxyConfig
{
    internal IntPtr host;
    internal int    port;
    internal IntPtr username;
    internal IntPtr password;
    internal int    type;
}

[StructLayout(LayoutKind.Sequential)]
internal struct NativeSecurityConfig
{
    public IntPtr additionalCaPem;
    public int additionalCaPemCount;
    public IntPtr additionalCaFile;
    [MarshalAs(UnmanagedType.I1)] public bool disableServerValidation;
    [MarshalAs(UnmanagedType.I1)] public bool sslVerbose;
    public long revocationCacheDurationSeconds;
}
