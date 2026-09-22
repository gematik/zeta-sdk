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

#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <regex>
#include <string>
#include <vector>

#ifdef _WIN32
#include "zeta_sdk_api.h"
#else
#include "libzeta_sdk_api.h"
#endif

#define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))

static const char* POPP_HEADER     = "PoPP";
static const char* API_EREZEPT     = "api/erezept";
static const char* PRODUCT_ID      = "ZETA-Test-Client";
static const char* PRODUCT_VERSION = "1.3.2";
static const char* CLIENT_NAME     = "cpp-client";

// ── Custom SMC-B connector example ───────────────────────────
// Uncomment to use a custom SMC-B connector instead of SM-B keystore.
// Replace the hardcoded values with your actual connector implementation.
//
// static const char* SMCB_CERTIFICATE_B64  = "<base64 DER certificate>";
// static const char* SMCB_SIGNATURE_DER_B64 = "<base64 DER signature>";
//
// void my_read_certificate(void* ctx, ZetaSdk_BytesCallback cb, void* cbCtx) {
//     auto derBytes = base64Decode(SMCB_CERTIFICATE_B64);
//     cb(cbCtx, derBytes.data(), (int)derBytes.size());
// }
//
// void my_external_authenticate(void* ctx, const char* base64Challenge,
//                                ZetaSdk_BytesCallback cb, void* cbCtx) {
//     auto sigBytes = base64Decode(SMCB_SIGNATURE_DER_B64);
//     cb(cbCtx, sigBytes.data(), (int)sigBytes.size());
// }
// ─────────────────────────────────────────────────────────────

void printResponse(const char* method, ZetaSdk_HttpResponse* response) {
    if (response->error == NULL) {
        std::cout << "[" << method << "] status: " << response->status << "\n";
        std::cout << "[" << method << "] body: " << (response->body ? response->body : "") << "\n";
    } else {
        std::cout << "[" << method << "] error: " << response->error << "\n";
    }
    std::cout.flush();
    ZetaHttpResponse_destroy(response);
}

void runSample(ZetaSdk_HttpClient* zetaHttpClient, char* poppToken) {
    std::cout << "Hello Zeta sample:\n";
    ZetaSdk_HttpHeader headers[] = { {(char*)POPP_HEADER, poppToken} };

    // GET hellozeta
    ZetaSdk_HttpRequest getRequest = { (char*)"hellozeta", NULL, headers, ARRAY_SIZE(headers) };
    printResponse("GET", (ZetaSdk_HttpResponse*)ZetaHttpClient_get(zetaHttpClient, &getRequest));

    std::cout << "Erezept CRUD sample:\n";

    // POST
    ZetaSdk_HttpRequest postRequest = {
            (char*)API_EREZEPT,
            (char*)"{\"prescriptionId\":\"RX-2025-000099\",\"patientId\":\"PAT-123456\",\"practitionerId\":\"PRAC-98765\","
                   "\"medicationName\":\"Ibuprofen 400 mg\",\"dosage\":\"1\",\"issuedAt\":\"2025-09-22T10:30:00Z\","
                   "\"expiresAt\":\"2025-12-31T23:59:59Z\",\"status\":\"CREATED\"}",
            headers, ARRAY_SIZE(headers)
    };
    ZetaSdk_HttpResponse* postResponse = (ZetaSdk_HttpResponse*)ZetaHttpClient_post(zetaHttpClient, &postRequest);
    std::cout << "[POST] status: " << postResponse->status << "\n";
    std::cout << "[POST] body: " << (postResponse->body ? postResponse->body : "") << "\n";

    std::string postBody = postResponse->body ? postResponse->body : "";
    ZetaHttpResponse_destroy(postResponse);

    std::regex re("\"id\"\\s*:\\s*(\\d+)");
    std::smatch match;
    if (!std::regex_search(postBody, match, re)) {
        std::cout << "Could not extract id from POST response — skipping PUT/DELETE\n";
        return;
    }
    std::string id = match[1];
    std::cout << "Using id=" << id << " for subsequent calls\n";

    char urlWithId[1024];
    snprintf(urlWithId, sizeof(urlWithId), "%s/%s", API_EREZEPT, id.c_str());

    // PUT
    ZetaSdk_HttpRequest putRequest = {
            urlWithId,
            (char*)"{\"prescriptionId\":\"RX-2025-000099\",\"patientId\":\"PAT-123456\",\"practitionerId\":\"PRAC-98765\","
                   "\"medicationName\":\"Ibuprofen 400 mg\",\"dosage\":\"2\",\"issuedAt\":\"2025-09-22T10:30:00Z\","
                   "\"expiresAt\":\"2025-12-31T23:59:59Z\",\"status\":\"UPDATED\"}",
            headers, ARRAY_SIZE(headers)
    };
    printResponse("PUT", (ZetaSdk_HttpResponse*)ZetaHttpClient_put(zetaHttpClient, &putRequest));

    // DELETE
    ZetaSdk_HttpRequest deleteRequest = { urlWithId, NULL, headers, ARRAY_SIZE(headers) };
    printResponse("DELETE", (ZetaSdk_HttpResponse*)ZetaHttpClient_delete(zetaHttpClient, &deleteRequest));

    // OPTIONS
    ZetaSdk_HttpRequest optionsRequest = { (char*)API_EREZEPT, NULL, headers, ARRAY_SIZE(headers) };
    printResponse("OPTIONS", (ZetaSdk_HttpResponse*)ZetaHttpClient_options(zetaHttpClient, &optionsRequest));

    // HEAD
    ZetaSdk_HttpRequest headRequest = { (char*)API_EREZEPT, NULL, headers, ARRAY_SIZE(headers) };
    printResponse("HEAD", (ZetaSdk_HttpResponse*)ZetaHttpClient_head(zetaHttpClient, &headRequest));

    std::cout << "Finish Erezept CRUD Sample\n";
}

void my_custom_log(void* ctx, const char* level, const char* tag, const char* message) {
    std::cout << "[" << level << "] [" << (tag ? tag : "Zeta") << "] " << message << "\n";
    std::cout.flush();
}

int main() {
    std::cout << "Hello Zeta from C++!\n";
    std::cout.flush();

    char* resource      = std::getenv("FACHDIENST_URL");
    char* keystoreFile  = std::getenv("SMB_KEYSTORE_FILE");
    char* alias         = std::getenv("SMB_KEYSTORE_ALIAS");
    char* password      = std::getenv("SMB_KEYSTORE_PASSWORD");
    char* poppToken     = std::getenv("POPP_TOKEN");
    char* aesB64Key     = std::getenv("STORAGE_AES_KEY");
    char* requiredRoleOid = std::getenv("REQUIRED_ROLE_OID");

    char* disableTlsValue = std::getenv("DISABLE_SERVER_VALIDATION");
    bool disableTls = disableTlsValue && strcmp(disableTlsValue, "true") == 0;

    const char* aslProdValue = std::getenv("ASL_PROD");
    bool aslProd = !aslProdValue || strcmp(aslProdValue, "true") == 0;

    char* scopes[] = {"zero:audience"};

    ZetaSdk_StorageConfig storageConfig = {
            aesB64Key,
            nullptr,
            nullptr,
    };

    ZetaSdk_TpmConfig tpmConfig = {};
    ZetaSdk_SmbConfig smbConfig = { keystoreFile, alias, password };

    // To use a custom SMC-B connector instead, replace smbConfig and smcbConfig with:
    // ZetaSdk_SmbConfig  smbConfig  = { nullptr, nullptr, nullptr };
    // ZetaSdk_SmcbVTable smcbVTable = { nullptr, my_read_certificate, my_external_authenticate };
    // ZetaSdk_SmcbConfig smcbConfig = { .customSmcb = &smcbVTable };
    ZetaSdk_SmcbConfig smcbConfig = {};

    ZetaSdk_AuthConfig authConfig = {
            .scopes             = scopes,
            .scopesCount        = ARRAY_SIZE(scopes),
            .exp                = 30,
            .aslProdEnvironment = aslProd,
            .smbConfig          = &smbConfig,
            .requiredOid        = requiredRoleOid,
            .smcbConfig         = &smcbConfig,
    };

    // Custom log callback
    ZetaSdk_LogVTable logVTable = {
            nullptr,
            my_custom_log,
            ZETA_LOG_LEVEL_DEBUG
    };

    ZetaSdk_ProxyConfig proxyConfig = {
            "127.0.0.1",
            8080,
            "user",     // username, NULL if not required
            "password", // password, NULL if not required
            0           // type: 0=HTTP
    };

    char* caPem[] = {
            const_cast<char*>(R"(-----BEGIN CERTIFICATE-----
...
                -----END CERTIFICATE-----)")
                    };

    const char* caPemFile = "/path/to/ca.crt";

    ZetaSdk_SecurityConfig security = {};
    security.additionalCaPem = const_cast<char**>(caPem);
    security.additionalCaPemCount = 1;
    //security.additionalCaFile = const_cast<char*>(caPemFile);
    security.disableServerValidation = disableTls;
    //security.sslVerbose = false;

    ZetaSdk_BuildConfig buildConfig = {
            resource,
            const_cast<char*>(PRODUCT_ID),
            const_cast<char*>(PRODUCT_VERSION),
            const_cast<char*>(CLIENT_NAME),
            &storageConfig, &tpmConfig, &authConfig,
            &logVTable,
            nullptr,
            &security
    };

    ZetaSdk_Client*     zetaSdkClient  = (ZetaSdk_Client*)ZetaSdk_buildZetaClient(&buildConfig);
    ZetaSdk_HttpClient* zetaHttpClient = (ZetaSdk_HttpClient*)ZetaSdk_buildHttpClient(zetaSdkClient);

    runSample(zetaHttpClient, poppToken);

    ZetaSdk_clearHttpClient(zetaHttpClient);
    ZetaSdk_clearZetaClient(zetaSdkClient);

    return 0;
}
