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
#include <iostream>

#include "hello_api.h"
#include "ws_client_api.h"

#define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))

char* stompConnectFrame(char* host) {
    char* format = "CONNECT\naccept-version:1.2\nhost:%s\n\n\u0000";
    char* buffer = new char[1024];
    if (int error = snprintf(buffer, sizeof(buffer), format, host) != 0) {
        std::cout << "Error formatting CONNECT frame: " << error << "\n";
        std::cout.flush();
        return NULL;
    }
    return buffer;
}

char* stompSubscribeFrame(char* id, char* destination) {
    char* format = "SUBSCRIBE\nid:%s\ndestination:%s\n\n\u0000";
    char* buffer = new char[1024];
    if (int error = snprintf(buffer, sizeof(buffer), format, id, destination) != 0) {
        std::cout << "Error formatting SUBSCRIBE frame: " << error << "\n";
        std::cout.flush();
        return NULL;
    }
    return buffer;
}

char* stompSendFrame(char* destination, char* bodyJson) {
    char* format = "SEND\ndestination:%s\ncontent-type:application/json\n\n%s\u0000";
    char* buffer = new char[1024];
    if (int error = snprintf(buffer, sizeof(buffer), format, destination, bodyJson) != 0) {
        std::cout << "Error formatting SEND frame: " << error << "\n";
        std::cout.flush();
        return NULL;
    }
    return buffer;
}

void sendPrescriptionCommands(ZetaSdk_WSSession* wsSession) {
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
            "}\n";

    char* sendFrame1 = stompSendFrame("/achelos_testfachdienst/app/erezept.create", createBody);
    ZetaSdk_WSSession_sendText(wsSession, sendFrame1);
    free(sendFrame1);

    char* sendFrame2 = stompSendFrame("/achelos_testfachdienst/app/erezept.read.1", "{}");
    ZetaSdk_WSSession_sendText(wsSession, sendFrame2);
    free(sendFrame2);
}

void connectAndSubscribe(ZetaSdk_WSSession* wsSession, char* host) {
    char* connectFrame = stompConnectFrame(host);
    ZetaSdk_WSSession_sendText(wsSession, connectFrame);
    free(connectFrame);

    ZetaSdk_WSMessage* message = ZetaSdk_WSSession_receiveNext(wsSession);
    if (message->type == WS_TEXT) {
        std::cout << "CONNECTED frame: \"" << message->data.text.text << "\"\n";
        std::cout.flush();
    }

    char* subscribeFrame1 = stompSubscribeFrame("sub-1", "/achelos_testfachdienst/topic/erezept");
    ZetaSdk_WSSession_sendText(wsSession, subscribeFrame1);
    free(subscribeFrame1);

    char* subscribeFrame2 = stompSubscribeFrame("sub-2", "/achelos_testfachdienst/user/queue/erezept");
    ZetaSdk_WSSession_sendText(wsSession, subscribeFrame2);
    free(subscribeFrame1);
}

void receiveMessages(ZetaSdk_WSSession* wsSession) {
    for (int i = 0; i < 10; i++) {
        ZetaSdk_WSMessage* incoming = ZetaSdk_WSSession_receiveNext(wsSession);

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

void wsHandler(ZetaSdk_WSSession* wsSession) {
    std::cout << "WSSession Handler: start\n";
    std::cout.flush();

    connectAndSubscribe(wsSession, "localhost");
    sendPrescriptionCommands(wsSession);
    receiveMessages(wsSession);
    ZetaSdk_WSSession_close(wsSession);

    std::cout << "WSSession Handler: end\n";
    std::cout.flush();
}

void runWSSessionSample(ZetaSdk_HttpClient* zetaHttpClient, char* wsBaseUrl) {
    std::cout << "WSSession Sample: start\n";
    std::cout.flush();

    ZetaSdk_WSSession_create(zetaHttpClient, wsBaseUrl, &wsHandler);

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

    ZetaSdk_HttpResponse* httpPostResponse = ZetaHttpClient_httpPost(zetaHttpClient, &httpPostRequest);

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
    ZetaSdk_HttpResponse* httpGetResponse = ZetaHttpClient_httpGet(zetaHttpClient, &httpGetRequest);
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

    char* resource = std::getenv("RESOURCE_URL");
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
    char* poppToken = std::getenv("POPP_TOKEN");

    const char* envValue = std::getenv("ASL_PROD");
    bool aslProdEnv = false;
    if (envValue == nullptr) {
        aslProdEnv = true;
    } else {
        aslProdEnv = (std::string(envValue).compare(std::string("true")) == 0);
    }

    char* productId = "demo_client";
    char* productVersion = "0.2.0";
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

    ZetaSdk_Client* zetaSdkClient = ZetaSdk_buildZetaClient(&buildConfig);
    ZetaSdk_HttpClient* zetaHttpClient = ZetaSdk_buildHttpClient(zetaSdkClient);

//    runWSSessionSample(zetaHttpClient, wsBaseUrl);
    runHttpClientSample(zetaHttpClient, poppToken);

    ZetaSdk_clearHttpClient(zetaHttpClient);
    ZetaSdk_clearZetaClient(zetaSdkClient);

    return 0;
}
