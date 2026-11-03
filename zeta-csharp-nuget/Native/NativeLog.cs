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

[StructLayout(LayoutKind.Sequential)]
internal struct NativeLogVTable
{
    public IntPtr context;
    public IntPtr log;
    public int    logLevel;
}

internal sealed class CustomLogHandle
{
    private readonly LogDelegate _log;

    private readonly GCHandle _selfHandle;

    public IntPtr VTablePtr { get; }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void LogDelegate(IntPtr ctx, IntPtr level, IntPtr tag, IntPtr message);

    public CustomLogHandle(Action<string, string?, string> logger, int logLevel = 0)
    {
        _selfHandle = GCHandle.Alloc(this);

        _log = (ctx, level, tag, message) =>
        {
            var lvl = Marshal.PtrToStringUTF8(level) ?? "";
            var t   = Marshal.PtrToStringUTF8(tag);
            var msg = Marshal.PtrToStringUTF8(message) ?? "";
            logger(lvl, t, msg);
        };

        var vtable = new NativeLogVTable
        {
            context = IntPtr.Zero,
            log     = Marshal.GetFunctionPointerForDelegate(_log),
            logLevel = (int)logLevel
        };

        VTablePtr = Marshal.AllocHGlobal(Marshal.SizeOf<NativeLogVTable>());
        Marshal.StructureToPtr(vtable, VTablePtr, false);
    }

    public void Free()
    {
        Marshal.FreeHGlobal(VTablePtr);
        if (_selfHandle.IsAllocated) _selfHandle.Free();
    }
}
