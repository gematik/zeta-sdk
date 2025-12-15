#ifndef HELLO_API_H
#define HELLO_API_H

#include "hello.h"

extern "C" ZetaSdk_Client* ZetaSdk_buildZetaClient(ZetaSdk_BuildConfig* buildConfig);
extern "C" void ZetaSdk_clearZetaClient(ZetaSdk_Client* sdkClient);

extern "C" ZetaSdk_HttpClient* ZetaSdk_buildHttpClient(ZetaSdk_Client* sdkClient);
extern "C" void ZetaSdk_clearHttpClient(ZetaSdk_HttpClient* httpClient);

extern "C" ZetaSdk_HttpResponse* ZetaHttpClient_httpGet(ZetaSdk_HttpClient* httpClient, ZetaSdk_HttpRequest* httpRequest);
extern "C" ZetaSdk_HttpResponse* ZetaHttpClient_httpPost(ZetaSdk_HttpClient* httpClient, ZetaSdk_HttpRequest* httpRequest);
extern "C" void ZetaHttpResponse_destroy(ZetaSdk_HttpResponse* httpResponse);

extern "C" void runRequest(ZetaSdk_HttpClient* httpClient, char* buffer, int size);

#endif
