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

internal sealed class NativeMem : IDisposable
{
    private readonly List<IntPtr> _ptrs = new();

    public IntPtr Str(string? s)
    {
        if (s is null) return IntPtr.Zero;
        var p = Marshal.StringToHGlobalAnsi(s);
        _ptrs.Add(p);
        return p;
    }

    public IntPtr Struct<T>(T value) where T : struct
    {
        var p = Marshal.AllocHGlobal(Marshal.SizeOf<T>());
        Marshal.StructureToPtr(value, p, false);
        _ptrs.Add(p);
        return p;
    }

    public (IntPtr ptr, int length) StringArray(IReadOnlyList<string> arr)
    {
        if (arr.Count == 0) return (IntPtr.Zero, 0);
        var p = Marshal.AllocHGlobal(IntPtr.Size * arr.Count);
        _ptrs.Add(p);
        for (int i = 0; i < arr.Count; i++)
            Marshal.WriteIntPtr(p, i * IntPtr.Size, Str(arr[i]));
        return (p, arr.Count);
    }

    public (IntPtr ptr, int length) HeaderArray(IReadOnlyDictionary<string, string> headers)
    {
        if (headers.Count == 0) return (IntPtr.Zero, 0);
        int structSize = Marshal.SizeOf<NativeHttpHeader>();
        var p = Marshal.AllocHGlobal(structSize * headers.Count);
        _ptrs.Add(p);
        int i = 0;
        foreach (var (k, v) in headers)
        {
            Marshal.StructureToPtr(
                new NativeHttpHeader { key = Str(k), value = Str(v) },
                IntPtr.Add(p, i++ * structSize), false);
        }
        return (p, headers.Count);
    }

    public void Dispose()
    {
        foreach (var p in _ptrs)
            if (p != IntPtr.Zero) Marshal.FreeHGlobal(p);
        _ptrs.Clear();
    }
}
