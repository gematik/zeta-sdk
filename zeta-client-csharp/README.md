# ZETA SDK - C# Client

C# bindings for the native ZETA SDK (Kotlin/Native), distributed as a NuGet package
(`ZetaSdk.Client`) and consumed via P/Invoke against the platform-specific shared library.

## Prerequisites

- [.NET 10 SDK](https://dotnet.microsoft.com/download)
- The native ZETA SDK, built for your target platform(s) (see below)

Install .NET on macOS:

```bash
brew update
brew install dotnet
```

## 1. Build the native SDK

From the `zeta-sdk` repository root:

```bash
cd ~/Workspace/zeta-sdk
./gradlew :zeta-sdk:linkDebugSharedMacosArm64
./gradlew :zeta-sdk:linkDebugSharedMacosX64
./gradlew :zeta-sdk:linkDebugSharedMingwX64
./gradlew :zeta-sdk:linkDebugSharedLinuxX64
```

You only need to build the target(s) matching the platform(s) you intend to run on.
The resulting shared library will be at:

```
build/bin/{osArch}/debugShared/
```

## 2. Place the native library

**This step must be repeated after every SDK rebuild** - the C# project does not
pick up native library changes automatically.

Copy the native library for your target platform into the `runtimes/` folder:

| Platform      | Source file                                          | Destination                  |
|---------------|------------------------------------------------------|------------------------------|
| macOS ARM64   | `build/bin/macosArm64/debugShared/libzeta_sdk.dylib` | `runtimes/osx-arm64/native/` |
| macOS x64     | `build/bin/macosX64/debugShared/libzeta_sdk.dylib`   | `runtimes/osx-x64/native/`   |
| Linux x64     | `build/bin/linuxX64/debugShared/libzeta_sdk.so`      | `runtimes/linux-x64/native/` |
| Windows x64   | `build/bin/mingwX64/debugShared/zeta_sdk.dll`        | `runtimes/win-x64/native/`   |

Example for macOS ARM64:

```bash
cp ../zeta-sdk/build/bin/macosArm64/debugShared/libzeta_sdk.dylib \
   runtimes/osx-arm64/native/libzeta_sdk.dylib
```

Once the file is in place, uncomment the corresponding `<Content>` entry in
`ZetaSdk.csproj` so the library is included in the build output and NuGet package:

```xml
<ItemGroup>
  <!-- Uncomment the block for your target platform -->

  <!--

  <Content Include="runtimes/osx-arm64/native/libzeta_sdk.dylib">
    <PackagePath>runtimes/osx-arm64/native/libzeta_sdk.dylib</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>

  <Content Include="runtimes/osx-x64/native/libzeta_sdk.dylib">
    <PackagePath>runtimes/osx-x64/native/libzeta_sdk.dylib</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>

  <Content Include="runtimes/linux-x64/native/libzeta_sdk.so">
    <PackagePath>runtimes/linux-x64/native/libzeta_sdk.so</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>

  <Content Include="runtimes/win-x64/native/zeta_sdk.dll">
    <PackagePath>runtimes/win-x64/native/zeta_sdk.dll</PackagePath>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
  </Content>
  -->
</ItemGroup>
```

## Project structure

```
zeta-client-csharp/
├── zeta-client-csharp.sln      # solution file
├── ZetaSdk.csproj              # class library (NuGet package)
├── ZetaClient.cs               # main SDK entry point
├── ZetaSdkException.cs         # exception types
├── Config/
│   └── ZetaClientConfig.cs     # client configuration model
├── Http/
│   ├── ZetaHttpClient.cs       # synchronous HTTP client wrapper
│   └── ZetaHttpClientAsync.cs  # async HTTP client wrapper
├── Native/
│   ├── NativeMem.cs            # native memory helpers
│   ├── NativeStructs.cs        # P/Invoke struct definitions
│   ├── ZetaNativeLoader.cs     # platform native library loader
│   └── ZetaSdkNative.cs        # P/Invoke declarations
├── Websocket/
│   └── WsSession.cs            # WebSocket / STOMP session
├── runtimes/                   # native libraries (not committed - copy manually)
│   ├── osx-arm64/native/
│   ├── osx-x64/native/
│   ├── linux-x64/native/
│   └── win-x64/native/
├── nupkg/                      # packed NuGet output
│   └── ZetaSdk.Client.0.5.0.nupkg
├── sample/
│   ├── sample.csproj           # executable sample
│   ├── nuget.config            # local NuGet source config
│   └── Program.cs              # sample entry point
└── tests/
    ├── ZetaSdk.Tests.csproj    # unit tests (xUnit)
    └── DelegateLifetimeTests.cs
```

## Public API overview

The main entry point is `ZetaClient`, built via `ZetaClient.Build(ZetaClientConfig)`.
All public types are documented with XML doc comments in the source - these show up
directly in your IDE (IntelliSense, Rider, etc.) once the NuGet package is referenced.

| Type / Method                        | Purpose                                                                                 |
|--------------------------------------|-----------------------------------------------------------------------------------------|
| `ZetaClient.Build(config)`           | Constructs and initializes a client from a `ZetaClientConfig`                           |
| `ZetaClient.Discover()`              | Runs OAuth/ASL service discovery against the resource server                            |
| `ZetaClient.Register()`              | Performs dynamic client registration                                                    |
| `ZetaClient.Authenticate()`          | Obtains an access token (DPoP-bound)                                                    |
| `ZetaClient.GetStatus()`             | Returns the current `ZetaSdkStatus` (registration/token state)                          |
| `ZetaClient.Logout()`                | Clears the current session's tokens                                                     |
| `ZetaClient.ClearRegistration()`     | Clears client registration, forcing re-registration                                     |
| `ZetaClient.CreateHttpClient()`      | Builds a synchronous HTTP client bound to this session (`ZetaHttpClient`)               |
| `ZetaClient.CreateHttpClientAsync()` | Builds an async HTTP client bound to this session (`ZetaHttpClientAsync`)               |
| `ZetaClient.OpenWebSocket(url, ...)` | Opens a WebSocket/STOMP session (`WsSession`) through the ZETA-authenticated connection |
| `ZetaClient.GetLastError()`          | Returns and clears the most recent native SDK error message                             |
| `ZetaClient.GetVersion()`            | Returns the linked native SDK version                                                   |
| `ZetaClientConfig`                   | Top-level configuration: resource, auth, storage, security, network, proxy              |

For detailed parameter-level documentation, see the XML doc comments on each type,
or generate the docs from `ZetaSdk.csproj` (`GenerateDocumentationFile` is enabled).

## Configuring the client

`ZetaClient.Build` takes a `ZetaClientConfig`. Only `Resource`, `Auth`, and
`Auth.RequiredRoleOid` are required - everything else has a default or is optional.

```csharp
var config = new ZetaClientConfig
{
    Resource = "https://popp.dev.poppservice.de",
    Auth = new ZetaAuthConfig
    {
        RequiredRoleOid = "1.2.276.0.76.4.50",
        Smb = new ZetaSmbConfig
        {
            KeystoreFile = "/path/to/your.p12",
            Alias        = "alias",
            Password     = "00"
        }
    }
};

using var client = ZetaClient.Build(config);
client.Discover();
client.Register();
client.Authenticate();
```

### `ZetaClientConfig`

| Property         | Type                               | Default                      | Notes                                                 |
|------------------|------------------------------------|------------------------------|-------------------------------------------------------|
| `Resource`       | `string` (required)                | -                            | Base URL of the protected resource (Fachdienst).      |
| `ProductId`      | `string`                           | `"zeta-client"`              | Sent as part of client attestation / posture data.    |
| `ProductVersion` | `string`                           | `"0.1.0"`                    | Sent as part of client attestation / posture data.    |
| `ClientName`     | `string`                           | `"zeta-cs-client"`           | Display name used during dynamic client registration. |
| `Auth`           | `ZetaAuthConfig` (required)        | -                            | See below.                                            |
| `Storage`        | `ZetaStorageConfig?`               | `null` → SDK default storage | See below.                                            |
| `Proxy`          | `ZetaProxyConfig?`                 | `null` → no proxy            | See below.                                            |
| `Logger`         | `Action<string, string?, string>?` | `null` → no logging          | Callback `(level, tag, message)`.                     |
| `LogLevel`       | `ZetaLogLevel`                     | `ZetaLogLevel.Error`         | Minimum level passed to `Logger`.                     |
| `Security`       | `SecurityConfig`                   | `new()`                      | See below.                                            |
| `Network`        | `NetworkConfig`                    | `new()`                      | See below.                                            |

### `ZetaAuthConfig`

| Property             | Type                        | Default              | Notes                                                              |
|----------------------|-----------------------------|----------------------|--------------------------------------------------------------------|
| `Scopes`             | `IReadOnlyList<string>`     | `["zero:audience"]`  | OAuth scopes requested.                                            |
| `ExpirySeconds`      | `int`                       | `30`                 | Client assertion `exp` lifetime.                                   |
| `AslProdEnvironment` | `bool`                      | `true`               | `false` uses the RU/test ASL environment.                          |
| `Smb`                | `ZetaSmbConfig?`            | `null`               | SM(C)-B keystore config for practitioner-side ("Stufe 1") clients. |
| `CustomSmcb`         | `ICustomSmcbConnector?`     | `null`               | Inject your own SMC-B connector instead of `Smb` (see below).      |
| `RequiredRoleOid`    | `string` (required)         | -                    | Required professional role OID for authentication.                 |

**`ZetaSmbConfig`** - `KeystoreFile`, `Alias`, `Password` (all required strings), pointing to a `.p12` keystore.

**`ICustomSmcbConnector`** - implement this to supply your own SMC-B/HSM integration instead of a local keystore file:

```csharp
public interface ICustomSmcbConnector
{
    Task<byte[]> ReadCertificateAsync();
    Task<byte[]> ExternalAuthenticateAsync(string base64Challenge);
}
```

### `ZetaStorageConfig`

| Property           | Type                | Default | Notes                                                                 |
|--------------------|---------------------|---------|-----------------------------------------------------------------------|
| `AesB64Key`        | `string?`           | `null`  | Base64-encoded AES-256 key used to encrypt the SDK's default storage. |
| `StoragePath`      | `string?`           | `null`  | Custom path for the default storage backend (platform-dependent).     |
| `CustomStorage`    | `ICustomStorage?`   | `null`  | Replace the default storage entirely with your own implementation.    |

**`ICustomStorage`** - implement this to back the SDK's persistence with your own store (e.g. a database, secure enclave, etc.) instead of the SDK's default platform storage:

```csharp
public interface ICustomStorage
{
    void Put(string key, string value);
    string? Get(string key);
    void Remove(string key);
    void Clear();
}
```

### `ZetaProxyConfig`

| Property   | Type                | Default              | Notes                |
|------------|---------------------|----------------------|----------------------|
| `Host`     | `string` (required) | -                    | Proxy hostname.      |
| `Port`     | `int` (required)    | -                    | Proxy port.          |
| `Username` | `string?`           | `null`               | Optional proxy auth. |
| `Password` | `string?`           | `null`               | Optional proxy auth. |
| `Type`     | `ZetaProxyType`     | `ZetaProxyType.Http` | `Http` or `Socks`.   |

### `SecurityConfig`

| Property                         | Type                     | Default                | Notes                                                     |
|----------------------------------|--------------------------|------------------------|-----------------------------------------------------------|
| `AdditionalCaPem`                | `IReadOnlyList<string>`  | `[]`                   | Additional trusted CA certificates, as PEM strings.       |
| `AdditionalCaFile`               | `string?`                | `null`                 | Path to an additional CA bundle file.                     |
| `DisableServerValidation`        | `bool`                   | `false`                | **Dev only.** Disables TLS server certificate validation. |
| `SslVerbose`                     | `bool`                   | `false`                | Enables verbose TLS/handshake logging.                    |
| `RevocationCacheDurationSeconds` | `long?`                  | `null` → SDK default   | OCSP/CRL revocation cache TTL.                            |

### `NetworkConfig`

| Property                   | Type    | Default           | Notes                                                             |
|----------------------------|---------|-------------------|-------------------------------------------------------------------|
| `ConnectTimeoutMillis`     | `long`  | `0` → SDK default |                                                                   |
| `RequestTimeoutMillis`     | `long`  | `0` → SDK default |                                                                   |
| `SocketTimeoutMillis`      | `long`  | `0` → SDK default |                                                                   |
| `MaxRetries`               | `int`   | `0`               |                                                                   |
| `RetryOnlyIdempotent`      | `bool`  | `true`            | Only retry GET/HEAD/OPTIONS/PUT/DELETE-style idempotent requests. |

### `ZetaLogLevel`

`Debug`, `Info`, `Warn`, `Error` (default), `None`. Passed to `Logger` alongside the
message tag; only messages at or above the configured level are emitted.

### `ZetaSdkStatus` (returned by `ZetaClient.GetStatus()`)

`NotRegistered`, `RegisteredNoValidTokens`, `HasRefreshToken`,
`HasAccessAndRefreshToken`, `Unknown`.

## Making requests

```csharp
using var http = client.CreateHttpClient();
var response = http.Get("/some/path");
if (response.IsSuccess)
{
    Console.WriteLine(response.Body);
}
```

`ZetaHttpClient` (sync) and `ZetaHttpClientAsync` (async, `...Async` suffix) both
expose `Get`, `Post`, `Put`, `Patch`, `Delete`, `Head`, `Options`. Both return/resolve
a `ZetaHttpResponse` with `Status`, `Body`, `Headers`, and the convenience booleans
`IsSuccess`, `IsClientError`, `IsServerError`. Errors at the transport level throw
`ZetaSdkException`.

## WebSockets / STOMP

```csharp
client.OpenWebSocket(wsUrl, headers: null, session =>
{
    var connectedFrame = session.StompConnect(host: "your-host");
    session.StompSubscribe(subscriptionId: "sub-0", contextPath: "/ws", destination: "/topic/foo");
    var messages = session.ReceiveMessages(count: 1);
});
```

`WsSession` exposes `SendText`, `SendBinary`, `ReceiveNext` (single message) and the
STOMP-specific helpers `StompConnect`, `StompSubscribe`, `StompSend`, and
`ReceiveMessages` (reads up to `count` text frames, stopping early on a `Close` frame).

For a complete, runnable example wiring all of the above together, see
[`sample/Program.cs`](sample/Program.cs).

## Build and run the sample

```bash
cd zeta-client-csharp/sample
POPP_TOKEN="..." \
SMB_KEYSTORE_FILE="/path/to/your.p12" \
SMB_KEYSTORE_ALIAS="alias" \
SMB_KEYSTORE_PASSWORD="00" \
FACHDIENST_URL="https://..." \
ZETA_SCOPES="zero:audience" \
WS_BASE_URL="wss://..." \
WS_SERVER_CONTEXT_PATH="/ws" \
dotnet run --project sample.csproj
```

Optional environment variables:

```bash
DISABLE_SERVER_VALIDATION=true   # disable TLS validation (dev only)
ASL_PROD=false                   # use RU environment
```

## Run the tests

```bash
dotnet test zeta-client-csharp/tests
```

## Pack as NuGet

```bash
dotnet pack ZetaSdk.csproj --configuration Release --output ./nupkg
```

Consume locally from another project:

```bash
dotnet nuget add source ./nupkg --name local-zeta
dotnet add package ZetaSdk.Client
```

The `sample/nuget.config` already points to the local `nupkg/` directory, so
the sample project picks up the package automatically after packing.

Publish to the NuGet feed:

```bash
dotnet nuget push nupkg/ZetaSdk.Client.0.5.0.nupkg \
  --source "https://gitlab...." \
  --api-key GITLAB_TOKEN
```

## Supported HTTP/WebSocket API

| Method            | Supported |
|-------------------|-----------|
| GET               | yes       |
| POST              | yes       |
| PUT               | yes       |
| PATCH             | yes       |
| DELETE            | yes       |
| HEAD              | yes       |
| OPTIONS           | yes       |
| WebSocket / STOMP | yes       |

## Troubleshooting

**`DllNotFoundException` / native library not found**
The native library wasn't copied into `runtimes/{platform}/native/`, or the
corresponding `<Content>` block in `ZetaSdk.csproj` is still commented out.
Repeat step 2 above after every SDK rebuild.
