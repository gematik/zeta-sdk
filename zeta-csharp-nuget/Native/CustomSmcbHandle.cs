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

namespace ZetaSdk.Native;

using ZetaSdk.Config;
using System.Runtime.InteropServices;

internal sealed class CustomSmcbHandle : IDisposable
{
    private readonly ICustomSmcbConnector _connector;
    private readonly GCHandle _selfHandle;

    private readonly NativeReadCertificateFn _readCertDelegate;
    private readonly NativeExternalAuthenticateFn _externalAuthDelegate;

    private readonly IntPtr _vtablePtr;
    public IntPtr VTablePtr => _vtablePtr;

    public CustomSmcbHandle(ICustomSmcbConnector connector)
    {
        _connector = connector;
        _selfHandle = GCHandle.Alloc(this);

        _readCertDelegate = ReadCertificateNative;
        _externalAuthDelegate = ExternalAuthenticateNative;

        var vtable = new NativeCustomSmcbVTable
        {
            context              = GCHandle.ToIntPtr(_selfHandle),
            readCertificate      = Marshal.GetFunctionPointerForDelegate(_readCertDelegate),
            externalAuthenticate = Marshal.GetFunctionPointerForDelegate(_externalAuthDelegate),
        };

        _vtablePtr = Marshal.AllocHGlobal(Marshal.SizeOf<NativeCustomSmcbVTable>());
        Marshal.StructureToPtr(vtable, _vtablePtr, false);
    }

    private static void ReadCertificateNative(IntPtr ctx, IntPtr cb, IntPtr cbCtx)
    {
        var self = (CustomSmcbHandle)GCHandle.FromIntPtr(ctx).Target!;
        _ = Task.Run(async () =>
        {
            var data = await TryInvokeAsync(() => self._connector.ReadCertificateAsync());
            FireCallback(cb, cbCtx, data);
        });
    }

    private static void ExternalAuthenticateNative(IntPtr ctx, IntPtr challenge, IntPtr cb, IntPtr cbCtx)
    {
        var self           = (CustomSmcbHandle)GCHandle.FromIntPtr(ctx).Target!;
        var challengeStr   = Marshal.PtrToStringAnsi(challenge) ?? string.Empty;
        _ = Task.Run(async () =>
        {
            var data = await TryInvokeAsync(() => self._connector.ExternalAuthenticateAsync(challengeStr));
            FireCallback(cb, cbCtx, data);
        });
    }

    private static async Task<byte[]> TryInvokeAsync(Func<Task<byte[]>> fn)
    {
        try   { return await fn(); }
        catch { return Array.Empty<byte>(); }
    }

    private static unsafe void FireCallback(IntPtr cb, IntPtr cbCtx, byte[] data)
    {
        var fn = Marshal.GetDelegateForFunctionPointer<NativeDataCallback>(cb);
        fixed (byte* ptr = data)
            fn(cbCtx, (IntPtr)ptr, data.Length);
    }

    public void Dispose()
    {
        Marshal.FreeHGlobal(_vtablePtr);
        if (_selfHandle.IsAllocated) _selfHandle.Free();
    }
}
