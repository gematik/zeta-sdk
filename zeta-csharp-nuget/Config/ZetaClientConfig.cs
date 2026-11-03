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

namespace ZetaSdk.Config;

/// <summary>
/// Top-level configuration for building a <see cref="ZetaSdk.ZetaClient"/>.
/// Only <see cref="Resource"/> and <see cref="Auth"/> are required.
/// </summary>
public sealed class ZetaClientConfig
{
    /// <summary>Base URL of the protected resource (Fachdienst).</summary>
    public required string Resource { get; init; }

    /// <summary>Product identifier sent as part of client attestation/posture data. Defaults to <c>"zeta-client"</c>.</summary>
    public string ProductId      { get; init; } = "zeta-client";

    /// <summary>Product version sent as part of client attestation/posture data. Defaults to <c>"0.1.0"</c>.</summary>
    public string ProductVersion { get; init; } = "0.1.0";

    /// <summary>Display name used during dynamic client registration. Defaults to <c>"zeta-cs-client"</c>.</summary>
    public string ClientName     { get; init; } = "zeta-cs-client";

    /// <summary>Authentication configuration: scopes, SMC-B, required role OID.</summary>
    public required ZetaAuthConfig Auth { get; init; }

    /// <summary>Storage configuration. If <c>null</c>, the SDK's default platform storage is used.</summary>
    public ZetaStorageConfig? Storage { get; init; }

    /// <summary>Optional HTTP/SOCKS proxy configuration. If <c>null</c>, no proxy is used.</summary>
    public ZetaProxyConfig? Proxy { get; init; }

    /// <summary>Optional log callback, invoked as <c>(level, tag, message)</c>. If <c>null</c>, logging is disabled.</summary>
    public Action<string, string?, string>? Logger { get; init; }

    /// <summary>Minimum severity passed to <see cref="Logger"/>. Defaults to <see cref="ZetaLogLevel.Error"/>.</summary>
    public ZetaLogLevel LogLevel { get; init; } = ZetaLogLevel.Error;

    /// <summary>TLS/security configuration. Defaults to a new <see cref="Config.SecurityConfig"/> with library defaults.</summary>
    public SecurityConfig Security { get; init; } = new();

    /// <summary>Network timeout/retry configuration. Defaults to a new <see cref="Config.NetworkConfig"/> with library defaults.</summary>
    public NetworkConfig Network { get; init; } = new();
}

/// <summary>
/// Authentication-related configuration: requested scopes, SMC-B credentials, and
/// the professional role required for authentication.
/// </summary>
public sealed class ZetaAuthConfig
{
    /// <summary>OAuth scopes to request. Defaults to <c>["zero:audience"]</c>.</summary>
    public IReadOnlyList<string> Scopes { get; init; } = ["zero:audience"];

    /// <summary>Lifetime, in seconds, of the client assertion's <c>exp</c> claim. Defaults to <c>30</c>.</summary>
    public int ExpirySeconds { get; init; } = 30;

    /// <summary>Whether to use the production ASL environment. <c>false</c> uses the RU/test environment. Defaults to <c>true</c>.</summary>
    public bool AslProdEnvironment { get; init; } = true;

    /// <summary>SM(C)-B keystore configuration, for practitioner-side ("Stufe 1") clients. Mutually exclusive in practice with <see cref="CustomSmcb"/>.</summary>
    public ZetaSmbConfig? Smb { get; init; }

    /// <summary>Custom SMC-B connector implementation, used instead of a local keystore (<see cref="Smb"/>).</summary>
    public ICustomSmcbConnector? CustomSmcb { get; init; }

    /// <summary>Required professional role OID for authentication.</summary>
    public required string RequiredRoleOid { get; init; }
}

/// <summary>SM(C)-B keystore location and credentials.</summary>
public sealed class ZetaSmbConfig
{
    /// <summary>Path to the <c>.p12</c> keystore file.</summary>
    public required string KeystoreFile { get; init; }

    /// <summary>Alias of the certificate entry within the keystore.</summary>
    public required string Alias        { get; init; }

    /// <summary>Password protecting the keystore.</summary>
    public required string Password     { get; init; }
}

/// <summary>
/// Storage configuration. By default, values are left <c>null</c> and the SDK
/// uses its own platform-appropriate encrypted storage.
/// </summary>
public class ZetaStorageConfig
{
    /// <summary>Base64-encoded AES-256 key used to encrypt the SDK's default storage.</summary>
    public string? AesB64Key { get; init; }

    /// <summary>Custom path for the default storage backend, if supported on the current platform.</summary>
    public string? StoragePath { get; init; }

    /// <summary>Custom storage implementation, used instead of the SDK's default platform storage.</summary>
    public ICustomStorage? CustomStorage { get; init; }
}

/// <summary>
/// Implement to supply a custom SMC-B/HSM integration instead of a local
/// keystore file (<see cref="ZetaSmbConfig"/>).
/// </summary>
public interface ICustomSmcbConnector
{
    /// <summary>Returns the SMC-B certificate to be used for authentication.</summary>
    Task<byte[]> ReadCertificateAsync();

    /// <summary>Performs an external authentication (e.g. card/HSM signing) for the given challenge.</summary>
    /// <param name="base64Challenge">The Base64-encoded challenge to sign/authenticate.</param>
    Task<byte[]> ExternalAuthenticateAsync(string base64Challenge);
}

/// <summary>Log severity levels, from most to least verbose.</summary>
public enum ZetaLogLevel
{
    /// <summary>Most verbose; includes detailed diagnostic information.</summary>
    Debug = 0,
    /// <summary>Informational messages about normal SDK operation.</summary>
    Info  = 1,
    /// <summary>Potentially problematic situations that do not prevent operation.</summary>
    Warn  = 2,
    /// <summary>Errors that affect the current operation.</summary>
    Error = 3,
    /// <summary>Disables logging entirely.</summary>
    None  = 4,
}

/// <summary>Proxy server configuration.</summary>
public sealed class ZetaProxyConfig
{
    /// <summary>Proxy hostname.</summary>
    public required string  Host     { get; init; }

    /// <summary>Proxy port.</summary>
    public required int     Port     { get; init; }

    /// <summary>Optional username for proxy authentication.</summary>
    public string? Username { get; init; }

    /// <summary>Optional password for proxy authentication.</summary>
    public string? Password { get; init; }

    /// <summary>Proxy protocol type. Defaults to <see cref="ZetaProxyType.Http"/>.</summary>
    public ZetaProxyType Type { get; init; } = ZetaProxyType.Http;
}

/// <summary>Supported proxy protocol types.</summary>
public enum ZetaProxyType
{
    /// <summary>HTTP proxy.</summary>
    Http  = 0,
    /// <summary>SOCKS proxy.</summary>
    Socks = 1,
}

/// <summary>TLS and revocation-checking configuration.</summary>
public sealed class SecurityConfig
{
    /// <summary>Additional trusted CA certificates, as PEM strings. Defaults to an empty list.</summary>
    public IReadOnlyList<string> AdditionalCaPem { get; init; } = [];

    /// <summary>Path to an additional CA bundle file.</summary>
    public string? AdditionalCaFile { get; init; }

    /// <summary>
    /// Disables TLS server certificate validation. <b>For development use only</b> —
    /// must not be enabled in production.
    /// </summary>
    public bool DisableServerValidation { get; init; }

    /// <summary>Enables verbose TLS/handshake logging.</summary>
    public bool SslVerbose { get; init; }

    /// <summary>OCSP/CRL revocation cache duration, in seconds. If <c>null</c>, the SDK default is used.</summary>
    public long? RevocationCacheDurationSeconds { get; init; }
}

/// <summary>HTTP client timeout and retry configuration.</summary>
public sealed class NetworkConfig
{
    /// <summary>Connection timeout, in milliseconds. <c>0</c> uses the SDK default.</summary>
    public long ConnectTimeoutMillis { get; init; }

    /// <summary>Overall request timeout, in milliseconds. <c>0</c> uses the SDK default.</summary>
    public long RequestTimeoutMillis { get; init; }

    /// <summary>Socket read/write timeout, in milliseconds. <c>0</c> uses the SDK default.</summary>
    public long SocketTimeoutMillis { get; init; }

    /// <summary>Maximum number of retry attempts for failed requests.</summary>
    public int  MaxRetries { get; init; }

    /// <summary>
    /// If <c>true</c>, only retries idempotent request methods (e.g. GET, HEAD,
    /// OPTIONS, PUT, DELETE). Defaults to <c>true</c>.
    /// </summary>
    public bool RetryOnlyIdempotent { get; init; } = true;
}
