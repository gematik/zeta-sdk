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

#ifdef _WIN32
    #include "zeta_sdk_api.h"
#else
    #include "libzeta_sdk_api.h"
#endif
#define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))
#define BUFFER_SIZE 1024

char* stompConnectFrame(const char* host) {
    char* format = "CONNECT\naccept-version:1.2\nhost:%s\n\n\00";
    char* buffer = new char[BUFFER_SIZE];
    int printedCount = snprintf(buffer, BUFFER_SIZE, format, host);
    if (printedCount <= 0) {
        std::cout << "Error formatting CONNECT frame: " << printedCount << "\n";
        std::cout.flush();
        return NULL;
    }
    return buffer;
}

char* stompSubscribeFrame(char* id, char* contextPath, char* destination) {
    char* format = "SUBSCRIBE\nid:%s\ndestination:%s%s\n\n\00";
    char* buffer = new char[BUFFER_SIZE];
    int printedCount = snprintf(buffer, BUFFER_SIZE, format, id, contextPath, destination);
    if (printedCount <= 0) {
        std::cout << "Error formatting SUBSCRIBE frame: " << printedCount << "\n";
        std::cout.flush();
        return NULL;
    }
    return buffer;
}

char* stompSendFrame(char* contextPath, char* destination, char* bodyJson) {
    char* format = "SEND\ndestination:%s%s\ncontent-type:application/json\n\n%s\00";
    char* buffer = new char[BUFFER_SIZE];
    int printedCount = snprintf(buffer, BUFFER_SIZE, format, contextPath, destination, bodyJson);
    if (printedCount <= 0) {
        std::cout << "Error formatting SEND frame: " << printedCount << "\n";
        std::cout.flush();
        return NULL;
    }
    return buffer;
}

void sendPrescriptionCommands(ZetaSdk_WSSession* wsSession, char* wsContextPath) {
    char* createBody =
            "\n{\n"
            "\"prescriptionId\": \"RX-2025-100123\",\n"
            "\"patientId\": \"PAT-123456\",\n"
            "\"practitionerId\": \"PRAC-98765\",\n"
            "\"medicationName\": \"Ibuprofen 400 mg\",\n"
            "\"dosage\": \"1\",\n"
            "\"issuedAt\": \"2025-09-22T10:30:00Z\",\n"
            "\"expiresAt\": \"2025-12-31T23:59:59Z\",\n"
            "\"status\": \"CREATED\"\n"
            "}\n\00";

    char* sendFrame1 = stompSendFrame(wsContextPath, "/app/erezept.create", createBody);
    int sendFrame1Size = strlen(sendFrame1) + 1;
    ZetaSdk_WSSession_sendBinary(wsSession, sendFrame1, sendFrame1Size);
    free(sendFrame1);

    char* sendFrame2 = stompSendFrame(wsContextPath, "/app/erezept.read.1", "{}");
    int sendFrame2Size = strlen(sendFrame2) + 1;
    ZetaSdk_WSSession_sendBinary(wsSession, sendFrame2, sendFrame2Size);
    free(sendFrame2);
}

void connectAndSubscribe(ZetaSdk_WSSession* wsSession, char* host, char* wsContextPath) {
    char* connectFrame = stompConnectFrame(host);
    int connectFrameSize = strlen(connectFrame) + 1;
    ZetaSdk_WSSession_sendBinary(wsSession, connectFrame, connectFrameSize);
    free(connectFrame);

    ZetaSdk_WSMessage* message = (ZetaSdk_WSMessage*)ZetaSdk_WSSession_receiveNext(wsSession);
    if (message->type == WS_TEXT) {
        std::cout << "CONNECTED frame: \"" << message->data.text.text << "\"\n";
        std::cout.flush();
    }

    char* subscribeFrame1 = stompSubscribeFrame("sub-1", wsContextPath, "/topic/erezept");
    int subscribeFrame1Size = strlen(subscribeFrame1) + 1;
    ZetaSdk_WSSession_sendBinary(wsSession, subscribeFrame1, subscribeFrame1Size);
    free(subscribeFrame1);

    char* subscribeFrame2 = stompSubscribeFrame("sub-2", wsContextPath, "/user/queue/erezept");
    int subscribeFrame2Size = strlen(subscribeFrame2) + 1;
    ZetaSdk_WSSession_sendBinary(wsSession, subscribeFrame2, subscribeFrame2Size);
    free(subscribeFrame2);
}

void receiveMessages(ZetaSdk_WSSession* wsSession) {
    for (int i = 0; i < 2; i++) {
        ZetaSdk_WSMessage* incoming = (ZetaSdk_WSMessage*)ZetaSdk_WSSession_receiveNext(wsSession);

        if (incoming->type == WS_CLOSE) {
            std::cout << "WebSocket closed.\n";
            std::cout.flush();
            break;
        } else if (incoming->type == WS_TEXT) {
            std::cout << "Received: " << incoming->data.text.text << "\n";
            std::cout.flush();
        } else if (incoming->type == WS_BINARY) {
            std::cout << "Received " << incoming->data.binary.size << " bytes (binary)\n";
            std::cout.flush();
        }

        ZetaSdk_WSMessage_destroy(incoming);
    }
}

void extractHost(char* url, char* host, size_t hostSize) {
    char* p = strstr(url, "://");
    if (p) p += 3;
    else p = url;

    char* end = strchr(p, '/');
    size_t len = end ? (size_t)(end - p) : strlen(p);

    if (len >= hostSize) len = hostSize - 1;

    strncpy(host, p, len);
    host[len] = '\0';
}

void runWSSessionSample(ZetaSdk_Client* zetaSdkClient, char* wsBaseUrl, char* wsServerContextPath, char* poppToken) {
    std::cout << "WSSession Sample: start\n";
    std::cout.flush();

    static char wsHost[BUFFER_SIZE];
    extractHost(wsBaseUrl, wsHost, BUFFER_SIZE);

    static char* wsContextPath = wsServerContextPath;
    void (*wsHandler)(ZetaSdk_WSSession *) = [](ZetaSdk_WSSession *wsSession) {
        std::cout << "WSSession Handler: start\n";
        std::cout.flush();

        connectAndSubscribe(wsSession, wsHost, wsContextPath);
        sendPrescriptionCommands(wsSession, wsContextPath);
        receiveMessages(wsSession);
        ZetaSdk_WSSession_close(wsSession);

        std::cout << "WSSession Handler: end\n";
        std::cout.flush();
    };

    ZetaSdk_HttpHeader httpHeaders[] = {
            {"PoPP", poppToken },
    };


    int wsBaseUrlLen = strlen(wsBaseUrl);
    ZetaSdk_Client_ws(zetaSdkClient, wsBaseUrl, wsBaseUrlLen, (void*)wsHandler, httpHeaders, ARRAY_SIZE(httpHeaders));

    std::cout << "WSSession Sample: end\n";
    std::cout.flush();
}

void runReceiptPost(ZetaSdk_HttpClient* zetaHttpClient, char* poppToken) {
    ZetaSdk_HttpHeader httpHeaders[] = {
            {"PoPP", poppToken },
    };
    ZetaSdk_HttpRequest httpPostRequest = {
            "api/erezept",
            "{\"prescriptionId\":\"RX-2025-000003\",\"patientId\":\"PAT-123456\",\"practitionerId\":\"PRAC-98765\",\"medicationName\":\"Ibuprofen 400 mg\",\"dosage\":\"1\",\"issuedAt\":\"2025-09-22T10:30:00Z\",\"expiresAt\":\"2025-12-31T23:59:59Z\",\"status\":\"CREATED\"}",
            httpHeaders,
            ARRAY_SIZE(httpHeaders)
    };

    ZetaSdk_HttpResponse* httpPostResponse = (ZetaSdk_HttpResponse*)ZetaHttpClient_post(zetaHttpClient, &httpPostRequest);

    if (httpPostResponse->error == NULL) {
        std::cout << "Response status: " << httpPostResponse->status << "\n";
        std::cout << "Response body: " << httpPostResponse->body << "\n";
        std::cout.flush();
    } else {
        std::cout << "Error during httpPost: " << httpPostResponse->error << "\n";
    }
}

void runHttpClientSample(ZetaSdk_HttpClient* zetaHttpClient, char* poppToken) {
    std::cout << "HttpClient Sample: start\n";
    std::cout.flush();

    ZetaSdk_HttpHeader httpHeaders[] = {
            {"PoPP", poppToken },
    };

    ZetaSdk_HttpRequest httpGetRequest = {
        "hellozeta",
        NULL,
        httpHeaders,
        ARRAY_SIZE(httpHeaders)
    };
    ZetaSdk_HttpResponse* httpGetResponse = (ZetaSdk_HttpResponse*)ZetaHttpClient_get(zetaHttpClient, &httpGetRequest);
    if (httpGetResponse->error == NULL) {
        std::cout << "Response status: " << httpGetResponse->status << "\n";
        std::cout << "Response body: " << httpGetResponse->body << "\n";
        std::cout.flush();
    } else {
        std::cout << "Error during httpGet: " << httpGetResponse->error << "\n";
    }
    ZetaHttpResponse_destroy(httpGetResponse);

    runReceiptPost(zetaHttpClient, poppToken);

    std::cout << "HttpClient Sample: end\n";
    std::cout.flush();
}

int main() {
    std::cout << "Hello from C++!\n";
    std::cout.flush();

    char* resource = std::getenv("FACHDIENST_URL");
    char* keystoreFile = std::getenv("SMB_KEYSTORE_FILE");
    char* alias = std::getenv("SMB_KEYSTORE_ALIAS");
    char* password = std::getenv("SMB_KEYSTORE_PASSWORD");

    char* baseUrl = std::getenv("SMCB_BASE_URL");
    char* mandantId = std::getenv("SMCB_MANDANT_ID");
    char* clientSystemId = std::getenv("SMCB_CLIENT_SYSTEM_ID");
    char* workspaceId = std::getenv("SMCB_WORKSPACE_ID");
    char* userId = std::getenv("SMCB_USER_ID");
    char* cardHandle = std::getenv("SMCB_CARD_HANDLE");

    char* wsBaseUrl = std::getenv("WS_BASE_URL");
    char* wsContextPath = std::getenv("WS_SERVER_CONTEXT_PATH");
    char* poppToken = std::getenv("POPP_TOKEN");

    char* disableTlsValue = std::getenv("DISABLE_SERVER_VALIDATION");
    bool disableTlsValidation = false;
    if (disableTlsValue == nullptr) {
      disableTlsValidation = false;
    } else {
      disableTlsValidation = strcmp(disableTlsValue, "true") == 0;
    }

    const char* envValue = std::getenv("ASL_PROD");
    bool aslProdEnv = false;
    if (envValue == nullptr) {
        aslProdEnv = true;
    } else {
        aslProdEnv = strcmp(envValue, "true") == 0;
    }

    char* productId = "demo-client";
    char* productVersion = "0.5.0";
    char* clientName = "sdk-client";
    char* scopes[] = {"zero:audience"};

    ZetaSdk_StorageConfig storageConfig = {};
    ZetaSdk_TpmConfig tpmConfig = {};
    ZetaSdk_SmbConfig smbConfig = {
            keystoreFile,
            alias,
            password
    };
    ZetaSdk_SmcbConfig smcbConfig = {
            baseUrl,
            mandantId,
            clientSystemId,
            workspaceId,
            userId,
            cardHandle
    };
    ZetaSdk_AuthConfig authConfig = {
            scopes,
            ARRAY_SIZE(scopes),
            30,
            aslProdEnv,
            &smbConfig,
            &smcbConfig
    };
    ZetaSdk_BuildConfig buildConfig = {
            resource,
            productId,
            productVersion,
            clientName,
            &storageConfig,
            &tpmConfig,
            &authConfig
    };

    ZetaSdk_Client* zetaSdkClient = (ZetaSdk_Client*)ZetaSdk_buildZetaClient(&buildConfig, disableTlsValidation);
    ZetaSdk_HttpClient* zetaHttpClient = (ZetaSdk_HttpClient*)ZetaSdk_buildHttpClient(zetaSdkClient);

    runHttpClientSample(zetaHttpClient, poppToken);
    runWSSessionSample(zetaSdkClient, wsBaseUrl, wsContextPath, poppToken);

    ZetaSdk_clearHttpClient(zetaHttpClient);
    ZetaSdk_clearZetaClient(zetaSdkClient);

    return 0;
}
