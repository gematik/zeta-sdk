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
internal struct NativeStorageVTable
{
    public IntPtr context;
    public IntPtr put;
    public IntPtr get;
    public IntPtr remove;
    public IntPtr clear;
}

internal sealed class CustomStorageHandle
{
    private readonly PutDelegate    _put;
    private readonly GetDelegate    _get;
    private readonly RemoveDelegate _remove;
    private readonly ClearDelegate  _clear;

    private readonly GCHandle _selfHandle;

    public IntPtr VTablePtr { get; }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void PutDelegate(IntPtr ctx, IntPtr key, IntPtr value, IntPtr cb, IntPtr cbCtx);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void GetDelegate(IntPtr ctx, IntPtr key, IntPtr cb, IntPtr cbCtx);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void RemoveDelegate(IntPtr ctx, IntPtr key, IntPtr cb, IntPtr cbCtx);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void ClearDelegate(IntPtr ctx, IntPtr cb, IntPtr cbCtx);

    public CustomStorageHandle(ICustomStorage storage)
    {
      _selfHandle = GCHandle.Alloc(this);

      _get = (ctx, key, cb, cbCtx) =>
      {
          var result = storage.Get(Marshal.PtrToStringUTF8(key)!);
          var resultPtr = result != null ? Marshal.StringToCoTaskMemUTF8(result) : IntPtr.Zero;
          Marshal.GetDelegateForFunctionPointer<StringCallbackDelegate>(cb)(cbCtx, resultPtr);
          if (resultPtr != IntPtr.Zero) Marshal.FreeCoTaskMem(resultPtr);
      };

      _put = (ctx, key, value, cb, cbCtx) =>
      {
          storage.Put(Marshal.PtrToStringUTF8(key)!, Marshal.PtrToStringUTF8(value)!);
          Marshal.GetDelegateForFunctionPointer<VoidCallbackDelegate>(cb)(cbCtx);
      };

      _remove = (ctx, key, cb, cbCtx) =>
      {
          storage.Remove(Marshal.PtrToStringUTF8(key)!);
          Marshal.GetDelegateForFunctionPointer<VoidCallbackDelegate>(cb)(cbCtx);
      };

      _clear = (ctx, cb, cbCtx) =>
      {
          storage.Clear();
          Marshal.GetDelegateForFunctionPointer<VoidCallbackDelegate>(cb)(cbCtx);
      };

        var vtable = new NativeStorageVTable
        {
            context = IntPtr.Zero,
            put     = Marshal.GetFunctionPointerForDelegate(_put),
            get     = Marshal.GetFunctionPointerForDelegate(_get),
            remove  = Marshal.GetFunctionPointerForDelegate(_remove),
            clear   = Marshal.GetFunctionPointerForDelegate(_clear),
        };

        VTablePtr = Marshal.AllocHGlobal(Marshal.SizeOf<NativeStorageVTable>());
        Marshal.StructureToPtr(vtable, VTablePtr, false);
    }

    public void Free()
    {
        Marshal.FreeHGlobal(VTablePtr);
        if (_selfHandle.IsAllocated) _selfHandle.Free();
    }
}

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate void VoidCallbackDelegate(IntPtr cbCtx);

[UnmanagedFunctionPointer(CallingConvention.Cdecl)]
internal delegate void StringCallbackDelegate(IntPtr cbCtx, IntPtr value);
