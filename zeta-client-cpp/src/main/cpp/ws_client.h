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

#ifndef WS_CLIENT_H
#define WS_CLIENT_H

typedef struct {
    void* zetaSdkWsSession;
} ZetaSdk_WSSession;

typedef enum {
    WS_TEXT,
    WS_BINARY,
    WS_CLOSE
} ZetaSdk_WsMessageType;

typedef struct {
    char* text;
    int size;
} ZetaSdk_WSMessage_Text;

typedef struct {
    char* bytes;
    int size;
} ZetaSdk_WSMessage_Binary;

typedef struct {
    ZetaSdk_WsMessageType type;
    union {
        ZetaSdk_WSMessage_Text text;
        ZetaSdk_WSMessage_Binary binary;
    } data;
} ZetaSdk_WSMessage;

typedef void (ZetaSdk_WSHandler)(ZetaSdk_WSSession*);

#endif
