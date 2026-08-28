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

internal static partial class ZetaSdkNative
{
    internal const string Lib = "zeta_sdk";

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaSdk_buildZetaClient(IntPtr buildConfig);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaSdk_buildHttpClient(IntPtr sdkClient);

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_clearHttpClient(IntPtr httpClient);

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_clearZetaClient(IntPtr sdkClient);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaHttpClient_get(IntPtr httpClient, ref NativeHttpRequest req);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaHttpClient_post(IntPtr httpClient, ref NativeHttpRequest req);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaHttpClient_put(IntPtr httpClient, ref NativeHttpRequest req);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaHttpClient_patch(IntPtr httpClient, ref NativeHttpRequest req);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaHttpClient_delete(IntPtr httpClient, ref NativeHttpRequest req);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaHttpClient_head(IntPtr httpClient, ref NativeHttpRequest req);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaHttpClient_options(IntPtr httpClient, ref NativeHttpRequest req);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpClient_getAsync(
      IntPtr httpClient, ref NativeHttpRequest req, IntPtr onSuccess, IntPtr onError);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpClient_postAsync(
        IntPtr httpClient, ref NativeHttpRequest req, IntPtr onSuccess, IntPtr onError);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpClient_putAsync(
        IntPtr httpClient, ref NativeHttpRequest req, IntPtr onSuccess, IntPtr onError);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpClient_patchAsync(
        IntPtr httpClient, ref NativeHttpRequest req, IntPtr onSuccess, IntPtr onError);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpClient_deleteAsync(
        IntPtr httpClient, ref NativeHttpRequest req, IntPtr onSuccess, IntPtr onError);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpClient_headAsync(
        IntPtr httpClient, ref NativeHttpRequest req, IntPtr onSuccess, IntPtr onError);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpClient_optionsAsync(
        IntPtr httpClient, ref NativeHttpRequest req, IntPtr onSuccess, IntPtr onError);

    [LibraryImport(Lib)]
    internal static partial void ZetaHttpResponse_destroy(IntPtr respPtr);

    [LibraryImport(Lib)]
    internal static partial int ZetaSdk_status(IntPtr client);

    [LibraryImport(Lib)]
    internal static partial int ZetaSdk_logout(IntPtr client);

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_Client_ws(
        IntPtr sdkClient,
        IntPtr url,
        int    urlLen,
        IntPtr handler,
        IntPtr customHeaders,
        int    customHeaderCount);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaSdk_WSSession_receiveNext(IntPtr wsSession);

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_WSSession_sendBinary(IntPtr wsSession, IntPtr binary, int size);

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_WSSession_sendText(IntPtr wsSession, IntPtr text, int textLen);

    [LibraryImport(Lib)]
    internal static partial int ZetaSdk_discover(IntPtr client);

    [LibraryImport(Lib)]
    internal static partial int ZetaSdk_register(IntPtr client);

    [LibraryImport(Lib)]
    internal static partial int ZetaSdk_authenticate(IntPtr client);

    [LibraryImport(Lib)]
    internal static partial int ZetaSdk_clearRegistration(IntPtr client);

    [LibraryImport(Lib)]
    internal static partial int ZetaSdk_close(IntPtr client);

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_WSSession_close(IntPtr wsSession);

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_WSMessage_destroy(IntPtr wsMessage);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaSdk_getLastError();

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_freeLastError(IntPtr ptr);

    [LibraryImport(Lib)]
    internal static partial IntPtr ZetaSdk_getVersion();

    [LibraryImport(Lib)]
    internal static partial void ZetaSdk_freeVersion(IntPtr ptr);

    static ZetaSdkNative()
    {
      NativeLibrary.SetDllImportResolver(
        typeof(ZetaSdkNative).Assembly,
        (libraryName, assembly, searchPath) =>
        {
            if (libraryName != Lib) return IntPtr.Zero;

            var rid = GetRid();
            var ext = GetExtension();
            var prefix = OperatingSystem.IsWindows() ? "" : "lib";

            var localPath = Path.Combine(
                AppContext.BaseDirectory,
                "runtimes", rid, "native",
                $"{prefix}{Lib}{ext}"
            );

            if (File.Exists(localPath))
                return NativeLibrary.Load(localPath);

            return NativeLibrary.Load(Lib);
        }
      );
    }

    private static string GetRid() =>
        (OperatingSystem.IsWindows(), OperatingSystem.IsMacOS(), RuntimeInformation.ProcessArchitecture) switch
        {
            (true,  _,     Architecture.X64)   => "win-x64",
            (true,  _,     Architecture.Arm64) => "win-arm64",
            (_,     true,  Architecture.X64)   => "osx-x64",
            (_,     true,  Architecture.Arm64) => "osx-arm64",
            _                                  => "linux-x64",
        };

    private static string GetExtension() =>
        OperatingSystem.IsWindows() ? ".dll" :
        OperatingSystem.IsMacOS()   ? ".dylib" : ".so";
}
