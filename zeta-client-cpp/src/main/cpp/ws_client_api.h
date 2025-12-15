#ifndef WS_CLIENT_API_H
#define WS_CLIENT_API_H

#include "ws_client.h"
#include "hello.h"

extern "C" void ZetaSdk_WSSession_create(ZetaSdk_HttpClient* sdkClient, char* url, ZetaSdk_WSHandler* handler);
extern "C" void ZetaSdk_WSSession_sendText(ZetaSdk_WSSession* wsSession, char* text);
extern "C" void ZetaSdk_WSSession_sendBinary(ZetaSdk_WSSession* wsSession, char* binary, int size);
extern "C" ZetaSdk_WSMessage* ZetaSdk_WSSession_receiveNext(ZetaSdk_WSSession* wsSession);
extern "C" void ZetaSdk_WSSession_close(ZetaSdk_WSSession* wsSession);

extern "C" void ZetaSdk_WSMessage_destroy(ZetaSdk_WSMessage* wsMessage);

#endif
