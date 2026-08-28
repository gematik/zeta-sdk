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

using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using ZetaSdk.Config;
using ZetaSdk.Native;

namespace ZetaSdk.Tests;

public class DelegateLifetimeTests
{
    [Fact]
    public void Gc_CollectsUnrootedObject()
    {
        var weak = CreateUnrootedObject();

        Collect();

        Assert.False(weak.IsAlive, "GC did not collect an unreachable object - the other tests in this class prove nothing");
    }

    [Fact]
    public void LogHandle_SurvivesGc_AndStillLogsThroughVTable()
    {
        var messages = new List<(string Level, string? Tag, string Message)>();
        var (handle, vTablePtr) = CreateLogHandle(messages);

        Collect();

        Assert.True(handle.IsAlive, "CustomLogHandle was collected while the native layer still holds its function pointer");

        InvokeLog(vTablePtr, "Debug", "ZETA-SDK", "getProtectedResource");

        Assert.Equal(("Debug", "ZETA-SDK", "getProtectedResource"), Assert.Single(messages));
    }

    [Fact]
    public void NativeConfigCallbacks_StayValid_AfterClientIsCollected()
    {
        var messages = new List<(string Level, string? Tag, string Message)>();
        var storage = new FakeStorage { ["token"] = "stored-value" };
        var config = CreateConfig(messages, storage);

        var built = BuildNativeConfigAndDropClient(config);

        Collect();

        Assert.False(built.Client.IsAlive, "the ZetaClient was still rooted - this test would not prove anything");
        Assert.True(built.LogHandle.IsAlive, "CustomLogHandle was collected together with the ZetaClient");
        Assert.True(built.StorageHandle.IsAlive, "CustomStorageHandle was collected together with the ZetaClient");

        InvokeLog(built.LogVTablePtr, "Debug", "ZETA-SDK", "call getProtectedResource from storage");
        Assert.Equal(("Debug", "ZETA-SDK", "call getProtectedResource from storage"), Assert.Single(messages));

        Assert.Equal("stored-value", InvokeStorageGet(built.StorageVTablePtr, "token"));
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static WeakReference CreateUnrootedObject() => new(new object(), trackResurrection: false);

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static (WeakReference Handle, IntPtr VTablePtr) CreateLogHandle(
        List<(string Level, string? Tag, string Message)> sink)
    {
        var handle = new CustomLogHandle((level, tag, message) => sink.Add((level, tag, message)));
        return (new WeakReference(handle, trackResurrection: false), handle.VTablePtr);
    }

    private sealed record BuiltConfig(
        WeakReference Client,
        WeakReference LogHandle,
        WeakReference StorageHandle,
        IntPtr LogVTablePtr,
        IntPtr StorageVTablePtr);

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static BuiltConfig BuildNativeConfigAndDropClient(ZetaClientConfig config)
    {
        var ctor = typeof(ZetaClient).GetConstructor(
            BindingFlags.Instance | BindingFlags.NonPublic, [typeof(IntPtr)])!;
        var client = ctor.Invoke([IntPtr.Zero]);

        using (var mem = new NativeMem())
        {
            typeof(ZetaClient)
                .GetMethod("BuildNativeConfig", BindingFlags.Instance | BindingFlags.NonPublic)!
                .Invoke(client, [config, mem]);
        }

        var logHandle = (CustomLogHandle)GetField(client, "_customLogHandle")!;
        var storageHandle = (CustomStorageHandle)GetField(client, "_customStorageHandle")!;

        return new BuiltConfig(
            new WeakReference(client, trackResurrection: false),
            new WeakReference(logHandle, trackResurrection: false),
            new WeakReference(storageHandle, trackResurrection: false),
            logHandle.VTablePtr,
            storageHandle.VTablePtr);
    }

    private static ZetaClientConfig CreateConfig(
        List<(string Level, string? Tag, string Message)> sink,
        ICustomStorage storage) => new()
    {
        Resource = "https://popp.dev.poppservice.de",
        Auth = new ZetaAuthConfig { RequiredRoleOid = "oid_praxis" },
        Storage = new ZetaStorageConfig { CustomStorage = storage },
        Logger = (level, tag, message) => sink.Add((level, tag, message)),
        LogLevel = ZetaLogLevel.Debug,
    };

    private static unsafe void InvokeLog(IntPtr vTablePtr, string level, string tag, string message)
    {
        var vTable = Marshal.PtrToStructure<NativeLogVTable>(vTablePtr);
        var log = (delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr, IntPtr, void>)vTable.log;

        var levelPtr = Marshal.StringToCoTaskMemUTF8(level);
        var tagPtr = Marshal.StringToCoTaskMemUTF8(tag);
        var messagePtr = Marshal.StringToCoTaskMemUTF8(message);
        try
        {
            log(vTable.context, levelPtr, tagPtr, messagePtr);
        }
        finally
        {
            Marshal.FreeCoTaskMem(levelPtr);
            Marshal.FreeCoTaskMem(tagPtr);
            Marshal.FreeCoTaskMem(messagePtr);
        }
    }

    private static unsafe string? InvokeStorageGet(IntPtr vTablePtr, string key)
    {
        var vTable = Marshal.PtrToStructure<NativeStorageVTable>(vTablePtr);
        var get = (delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr, IntPtr, void>)vTable.get;

        string? result = null;
        StringCallbackDelegate callback = (_, value) => result = Marshal.PtrToStringUTF8(value);

        var keyPtr = Marshal.StringToCoTaskMemUTF8(key);
        try
        {
            get(vTable.context, keyPtr, Marshal.GetFunctionPointerForDelegate(callback), IntPtr.Zero);
        }
        finally
        {
            Marshal.FreeCoTaskMem(keyPtr);
            GC.KeepAlive(callback);
        }

        return result;
    }

    private static void Collect()
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
    }

    private static object? GetField(object target, string name) =>
        target.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(target);

    private sealed class FakeStorage : ICustomStorage
    {
        private readonly Dictionary<string, string> _entries = new();

        public string this[string key] { set => _entries[key] = value; }

        public void Put(string key, string value) => _entries[key] = value;
        public string? Get(string key) => _entries.GetValueOrDefault(key);
        public void Remove(string key) => _entries.Remove(key);
        public void Clear() => _entries.Clear();
    }
}
