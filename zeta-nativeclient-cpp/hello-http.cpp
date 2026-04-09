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

#ifdef _WIN32
    #include "zeta_sdk_api.h"
#else
    #include "libzeta_sdk_api.h"
#endif

#define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))

static const char* POPP_HEADER       = "PoPP";
static const char* API_EREZEPT       = "api/erezept";
static const char* PRODUCT_ID        = "demo-client";
static const char* PRODUCT_VERSION   = "0.5.0";
static const char* CLIENT_NAME       = "sdk-client";

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

int main() {
    std::cout << "Hello Zeta from C++!\n";
    std::cout.flush();

    char* resource      = std::getenv("FACHDIENST_URL");
    char* keystoreFile  = std::getenv("SMB_KEYSTORE_FILE");
    char* alias         = std::getenv("SMB_KEYSTORE_ALIAS");
    char* password      = std::getenv("SMB_KEYSTORE_PASSWORD");
    char* baseUrl       = std::getenv("SMCB_BASE_URL");
    char* mandantId     = std::getenv("SMCB_MANDANT_ID");
    char* clientSystemId = std::getenv("SMCB_CLIENT_SYSTEM_ID");
    char* workspaceId   = std::getenv("SMCB_WORKSPACE_ID");
    char* userId        = std::getenv("SMCB_USER_ID");
    char* cardHandle    = std::getenv("SMCB_CARD_HANDLE");
    char* poppToken     = std::getenv("POPP_TOKEN");

    char* disableTlsValue = std::getenv("DISABLE_SERVER_VALIDATION");
    bool disableTls = disableTlsValue && strcmp(disableTlsValue, "true") == 0;

    const char* aslProdValue = std::getenv("ASL_PROD");
    bool aslProd = !aslProdValue || strcmp(aslProdValue, "true") == 0;

    const char* scopes[] = {"zero:audience"};

    ZetaSdk_StorageConfig storageConfig = {};
    ZetaSdk_TpmConfig tpmConfig = {};
    ZetaSdk_SmbConfig smbConfig = { keystoreFile, alias, password };
    ZetaSdk_SmcbConfig smcbConfig = { baseUrl, mandantId, clientSystemId, workspaceId, userId, cardHandle };
    ZetaSdk_AuthConfig authConfig = {
            const_cast<char**>(scopes), ARRAY_SIZE(scopes), 30, aslProd, &smbConfig, &smcbConfig
    };
    ZetaSdk_BuildConfig buildConfig = {
            resource,
            const_cast<char*>(PRODUCT_ID),
            const_cast<char*>(PRODUCT_VERSION),
            const_cast<char*>(CLIENT_NAME),
            &storageConfig, &tpmConfig, &authConfig
    };

    ZetaSdk_Client* zetaSdkClient = (ZetaSdk_Client*)ZetaSdk_buildZetaClient(&buildConfig, disableTls);
    ZetaSdk_HttpClient* zetaHttpClient = (ZetaSdk_HttpClient*)ZetaSdk_buildHttpClient(zetaSdkClient);

    runSample(zetaHttpClient, poppToken);

    ZetaSdk_clearHttpClient(zetaHttpClient);
    ZetaSdk_clearZetaClient(zetaSdkClient);

    return 0;
}
