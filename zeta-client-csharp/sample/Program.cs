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

using ZetaSdk;
using ZetaSdk.Config;

var failed = false;

bool disableTls = string.Equals(
    Environment.GetEnvironmentVariable("DISABLE_SERVER_VALIDATION"), "true",
    StringComparison.OrdinalIgnoreCase);

var config = new ZetaClientConfig
{
    Resource       = Env("FACHDIENST_URL"),
    ProductId      = "ZETA-Test-Client",
    ProductVersion = "1.3.0",
    ClientName     = "sdk-client",
    Storage = new ZetaStorageConfig
    {
      CustomStorage = new InMemoryStorage(),
      AesB64Key = Env("STORAGE_AES_KEY"),
    },
    Auth = new ZetaAuthConfig
    {
        Scopes = ["zero:audience"],
        AslProdEnvironment = string.Equals(Env("ASL_PROD", "true"), "true"),
         Smb = new ZetaSmbConfig
         {
             KeystoreFile = Env("SMB_KEYSTORE_FILE"),
             Alias        = Env("SMB_KEYSTORE_ALIAS"),
             Password     = Env("SMB_KEYSTORE_PASSWORD")
         },
        //CustomSmcb = new MyCustomConnector(),
        RequiredRoleOid = Env("REQUIRED_ROLE_OID")
     },
     /*Proxy = new ZetaProxyConfig
     {
         Host     = "127.0.0.1",
         Port     = 8080,
         Username = "user",
         Password = "password",
         Type     = ZetaProxyType.Http
     },*/
     Logger = (level, tag, message) =>
     {
         Console.WriteLine($"[{level}] [{tag ?? "Zeta"}] {message}");
     },
     LogLevel = Enum.TryParse<ZetaLogLevel>(
         Environment.GetEnvironmentVariable("LOG_LEVEL"),
         ignoreCase: true,
         out var logLevel) ? logLevel : ZetaLogLevel.Info,
     Security = new SecurityConfig
      {
          /*AdditionalCaPem = [
                    """
            -----BEGIN CERTIFICATE-----
            ...
            -----END CERTIFICATE-----
            """
                ],*/
          // AdditionalCaFile = "/path/to/ca.crt",
          DisableServerValidation = disableTls,
          SslVerbose = false,
          RevocationCacheDurationSeconds = 3600,
      },
       Network = new NetworkConfig
       {
           ConnectTimeoutMillis = 15_000,
           RequestTimeoutMillis = 120_000,
           SocketTimeoutMillis  = 120_000, // >= RequestTimeoutMillis, see warning below
           MaxRetries           = 2,
           RetryOnlyIdempotent  = true,
       },
};

string poppToken    = Env("POPP_TOKEN");
string wsBaseUrl    = Env("WS_BASE_URL");
string wsContextPath= Env("WS_SERVER_CONTEXT_PATH");

ZetaClient? client = null;
try
{
    client = ZetaClient.Build(config);
}
catch (ZetaSdkException ex)
{
    Console.Error.WriteLine($"Error while building SDK: {ex.Message}");
    Environment.Exit(1);
    return;
}

// Http sample
Console.WriteLine("HTTP sample");
using var http = client.CreateHttpClientAsync();

var headers = new Dictionary<string, string> { ["PoPP"] = poppToken };

// GET
try { Console.WriteLine($"[GET] - {await http.GetAsync("hellozeta", headers)}"); }
catch (Exception ex) { Console.Error.WriteLine($"[FAIL] GET: {ex.Message}"); failed = true; }

// POST
try
{
    const string createJson =
      """{"prescriptionId":"RX-2025-000099","patientId":"PAT-123456","practitionerId":"PRAC-98765","medicationName":"Ibuprofen 400 mg","dosage":"1","issuedAt":"2025-09-22T10:30:00Z","expiresAt":"2025-12-31T23:59:59Z","status":"CREATED"}""";

    var postResp = await http.PostAsync("api/erezept", createJson, headers);
    Console.WriteLine($"[POST] - {postResp}");

    var idMatch = System.Text.RegularExpressions.Regex.Match(postResp.Body, @"""id""\s*:\s*(\d+)");
    if (!idMatch.Success)
    {
        Console.WriteLine("Could not extract id from POST response — skipping PUT/DELETE");
    }
    else
    {
        var id  = idMatch.Groups[1].Value;
        var url = $"api/erezept/{id}";
        Console.WriteLine($"Using id={id} for subsequent calls");

        // PUT
        const string updateJson =
            """{"prescriptionId":"RX-2025-000099","patientId":"PAT-123456","practitionerId":"PRAC-98765","medicationName":"Ibuprofen 400 mg","dosage":"2","issuedAt":"2025-09-22T10:30:00Z","expiresAt":"2025-12-31T23:59:59Z","status":"UPDATED"}""";

          Console.WriteLine($"[PUT] - {await http.PutAsync(url, updateJson, headers)}");

          // DELETE
          Console.WriteLine($"[DELETE] - {await http.DeleteAsync(url, headers)}");
    }
}
catch (Exception ex) { Console.Error.WriteLine($"[FAIL] POST/PUT/DELETE: {ex.Message}"); failed = true; }

// OPTIONS
try { Console.WriteLine($"[OPTIONS] - {await http.OptionsAsync("api/erezept", headers)}"); }
catch (Exception ex) { Console.Error.WriteLine($"[FAIL] OPTIONS: {ex.Message}"); failed = true; }

// HEAD
try { Console.WriteLine($"[HEAD] - {await http.HeadAsync("api/erezept", headers)}"); }
catch (Exception ex) { Console.Error.WriteLine($"[FAIL] HEAD: {ex.Message}"); failed = true; }

// WebSockets sample
Console.WriteLine("\nWebSocket");

try
{
    string wsHost = new Uri(wsBaseUrl).Host;
    var result = client.OpenWebSocket(wsBaseUrl, headers, session =>
    {
        var connected = session.StompConnect(wsHost);
        Console.WriteLine($"CONNECTED: {connected.Trim()}");

        session.StompSubscribe("sub-1", wsContextPath, "/topic/erezept");
        session.StompSubscribe("sub-2", wsContextPath, "/user/queue/erezept");

        const string createBody =
            """
            {
            "prescriptionId": "RX-2025-100123",
            "patientId": "PAT-123456",
            "practitionerId": "PRAC-98765",
            "medicationName": "Ibuprofen 400 mg",
            "dosage": "1",
            "issuedAt": "2025-09-22T10:30:00Z",
            "expiresAt": "2025-12-31T23:59:59Z",
            "status": "CREATED"
            }
            """;

        session.StompSend(wsContextPath, "/app/erezept.create", createBody);
        session.StompSend(wsContextPath, "/app/erezept.read.1", "{}");

        var messages = session.ReceiveMessages(count: 2);
        foreach (var msg in messages)
            Console.WriteLine($"Received: {msg}");

        session.Close();
    });
    if (result != 0) { Console.Error.WriteLine("[FAIL] WebSocket failed - check logs for details"); failed = true; }
}
catch (Exception ex) { Console.Error.WriteLine($"[FAIL] WebSocket: {ex.Message}"); failed = true; }

Console.WriteLine(failed ? "\n[FAILED]" : "\n[SUCCESS]");
Environment.Exit(failed ? 1 : 0);

static string Env(string key, string fallback = "")
    => Environment.GetEnvironmentVariable(key) ?? fallback;
