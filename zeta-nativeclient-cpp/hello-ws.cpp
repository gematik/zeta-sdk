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
#include <string>
#include <regex>
#include <ctime>

#ifdef _WIN32
#include "zeta_sdk_api.h"
#else
#include "libzeta_sdk_api.h"
#endif

#define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))
#define BUFFER_SIZE 1024

static const char* POPP_HEADER        = "PoPP";
static const char* PRODUCT_ID         = "demo-client";
static const char* PRODUCT_VERSION    = "0.5.0";
static const char* CLIENT_NAME        = "sdk-client";
static const char* SCOPE_ZERO         = "zero:audience";

char* stompConnectFrame(const char* host) {
    char* buffer = new char[BUFFER_SIZE];
    snprintf(buffer, BUFFER_SIZE, "CONNECT\naccept-version:1.2\nhost:%s\n\n\00", host);
    return buffer;
}

char* stompSubscribeFrame(char* id, char* contextPath, char* destination) {
    char* buffer = new char[BUFFER_SIZE];
    snprintf(buffer, BUFFER_SIZE, "SUBSCRIBE\nid:%s\ndestination:%s%s\n\n\00", id, contextPath, destination);
    return buffer;
}

char* stompSendFrame(char* contextPath, char* destination, char* bodyJson) {
    char* buffer = new char[BUFFER_SIZE];
    snprintf(buffer, BUFFER_SIZE, "SEND\ndestination:%s%s\ncontent-type:application/json\n\n%s\00", contextPath, destination, bodyJson);
    return buffer;
}

void extractHost(char* url, char* host, size_t hostSize) {
    char* p = strstr(url, "://");
    if (p) p += 3; else p = url;
    char* end = strchr(p, '/');
    size_t len = end ? (size_t)(end - p) : strlen(p);
    if (len >= hostSize) len = hostSize - 1;
    strncpy(host, p, len);
    host[len] = '\0';
}

std::string receiveTextFrame(ZetaSdk_WSSession* wsSession) {
    ZetaSdk_WSMessage* msg = (ZetaSdk_WSMessage*)ZetaSdk_WSSession_receiveNext(wsSession);
    std::string text;
    if (msg->type == WS_TEXT && msg->data.text.text != nullptr)
        text = std::string(msg->data.text.text);
    ZetaSdk_WSMessage_destroy(msg);
    return text;
}

long extractId(const std::string& frame) {
    size_t jsonStart = frame.find('{');
    if (jsonStart == std::string::npos) return -1;
    std::string body = frame.substr(jsonStart);
    std::regex re("\"id\"\\s*:\\s*(\\d+)");
    std::smatch match;
    if (!std::regex_search(body, match, re)) return -1;
    return std::stol(match[1]);
}

void connectAndSubscribe(ZetaSdk_WSSession* wsSession, char* host, char* wsContextPath) {
    char* connectFrame = stompConnectFrame(host);
    ZetaSdk_WSSession_sendBinary(wsSession, (void*)connectFrame, strlen(connectFrame) + 1);
    free(connectFrame);

    receiveTextFrame(wsSession);
    std::cout << "STOMP connected\n";

    char* sub1 = stompSubscribeFrame((char*)"sub-1", wsContextPath, (char*)"/topic/erezept");
    ZetaSdk_WSSession_sendBinary(wsSession, (void*)sub1, strlen(sub1) + 1);
    free(sub1);

    char* sub2 = stompSubscribeFrame((char*)"sub-2", wsContextPath, (char*)"/user/queue/erezept");
    ZetaSdk_WSSession_sendBinary(wsSession, (void*)sub2, strlen(sub2) + 1);
    free(sub2);
}

void runPrescriptionCRUD(ZetaSdk_WSSession* wsSession, char* wsContextPath) {
    char prescriptionId[BUFFER_SIZE];
    snprintf(prescriptionId, BUFFER_SIZE, "RX-2025-%ld", (long)time(nullptr));

    char createBodyBuf[BUFFER_SIZE * 2];
    snprintf(createBodyBuf, sizeof(createBodyBuf),
             "\n{\n"
             "\"prescriptionId\": \"%s\",\n"
             "\"patientId\": \"PAT-123456\",\n"
             "\"practitionerId\": \"PRAC-98765\",\n"
             "\"medicationName\": \"Ibuprofen 400 mg\",\n"
             "\"dosage\": \"1\",\n"
             "\"issuedAt\": \"2025-09-22T10:30:00Z\",\n"
             "\"expiresAt\": \"2025-12-31T23:59:59Z\",\n"
             "\"status\": \"CREATED\"\n"
             "}\n", prescriptionId);

    char* createFrame = stompSendFrame(wsContextPath, (char*)"/app/erezept.create", createBodyBuf);
    ZetaSdk_WSSession_sendBinary(wsSession, (void*)createFrame, strlen(createFrame) + 1);
    free(createFrame);

    std::string createResponse = receiveTextFrame(wsSession);
    long createdId = extractId(createResponse);
    if (createdId < 0) {
        std::cout << "Failed to extract id from create response\n";
        return;
    }
    std::cout << "Created prescription id=" << createdId << "\n";

    char readDestination[BUFFER_SIZE];
    snprintf(readDestination, BUFFER_SIZE, "/app/erezept.read.%ld", createdId);
    char* readFrame = stompSendFrame(wsContextPath, readDestination, (char*)"{}");
    ZetaSdk_WSSession_sendBinary(wsSession, (void*)readFrame, strlen(readFrame) + 1);
    free(readFrame);

    std::string readResponse = receiveTextFrame(wsSession);
    long readId = extractId(readResponse);
    if (readId == createdId)
        std::cout << "Read prescription id=" << readId << " OK\n";
    else
        std::cout << "Read id mismatch: expected=" << createdId << " got=" << readId << "\n";
}

static char g_wsHost[BUFFER_SIZE];
static char* g_wsContextPath;

void runWSSessionSample(ZetaSdk_Client* zetaSdkClient, char* wsBaseUrl, char* wsServerContextPath, char* poppToken) {
    extractHost(wsBaseUrl, g_wsHost, BUFFER_SIZE);
    g_wsContextPath = wsServerContextPath;

    void (*wsHandler)(ZetaSdk_WSSession*) = [](ZetaSdk_WSSession* wsSession) {
        std::cout << "WSSession: start\n";
        connectAndSubscribe(wsSession, g_wsHost, g_wsContextPath);
        runPrescriptionCRUD(wsSession, g_wsContextPath);
        ZetaSdk_WSSession_close(wsSession);
        std::cout << "WSSession: end\n";
    };

    ZetaSdk_HttpHeader httpHeaders[] = { {(char*)POPP_HEADER, poppToken} };
    ZetaSdk_Client_ws(zetaSdkClient, (void*)wsBaseUrl, strlen(wsBaseUrl), (void*)wsHandler, httpHeaders, ARRAY_SIZE(httpHeaders));
}

int main() {
    std::cout << "Hello Zeta from C++ WebSocket!\n";

    char* resource      = std::getenv("FACHDIENST_URL");
    char* keystoreFile  = std::getenv("SMB_KEYSTORE_FILE");
    char* alias         = std::getenv("SMB_KEYSTORE_ALIAS");
    char* password      = std::getenv("SMB_KEYSTORE_PASSWORD");
    char* wsBaseUrl     = std::getenv("WS_BASE_URL");
    char* wsContextPath = std::getenv("WS_SERVER_CONTEXT_PATH");
    char* poppToken     = std::getenv("POPP_TOKEN");

    char* disableTlsValue = std::getenv("DISABLE_SERVER_VALIDATION");
    bool disableTls = disableTlsValue && strcmp(disableTlsValue, "true") == 0;

    const char* aslProdValue = std::getenv("ASL_PROD");
    bool aslProd = !aslProdValue || strcmp(aslProdValue, "true") == 0;

    char* scopes[] = {(char*)SCOPE_ZERO};
    ZetaSdk_StorageConfig storageConfig = {};
    ZetaSdk_TpmConfig tpmConfig = {};
    ZetaSdk_SmbConfig smbConfig = { keystoreFile, alias, password };
    ZetaSdk_AuthConfig authConfig = { scopes, ARRAY_SIZE(scopes), 30, aslProd, &smbConfig, nullptr };
    ZetaSdk_BuildConfig buildConfig = {
            resource,
            const_cast<char*>(PRODUCT_ID),
            const_cast<char*>(PRODUCT_VERSION),
            const_cast<char*>(CLIENT_NAME),
            &storageConfig, &tpmConfig, &authConfig
    };

    ZetaSdk_Client* sdkClient = (ZetaSdk_Client*)ZetaSdk_buildZetaClient(&buildConfig, disableTls);
    runWSSessionSample(sdkClient, wsBaseUrl, wsContextPath, poppToken);
    ZetaSdk_clearZetaClient(sdkClient);

    return 0;
}
