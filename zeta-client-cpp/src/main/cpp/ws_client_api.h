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
