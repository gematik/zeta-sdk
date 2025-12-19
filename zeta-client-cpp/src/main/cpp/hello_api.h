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
