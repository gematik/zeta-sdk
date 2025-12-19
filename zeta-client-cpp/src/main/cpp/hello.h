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

#ifndef HELLO_H
#define HELLO_H

#include <stdbool.h>

typedef struct {
} ZetaSdk_TpmConfig;

typedef struct {
} ZetaSdk_StorageConfig;

typedef struct {
    char* keystoreFile;
    char* alias;
    char* password;
} ZetaSdk_SmbConfig;

typedef struct {
    char* baseUrl;
    char* mandantId;
    char* clientSystemId;
    char* workspaceId;
    char* userId;
    char* cardHandle;
} ZetaSdk_SmcbConfig;

typedef struct {
    char** scopes;
    int scopesCount;
    long exp;
    bool enableAslTracingHeader;
    ZetaSdk_SmbConfig* smbConfig;
    ZetaSdk_SmcbConfig* smcbConfig;
} ZetaSdk_AuthConfig;

typedef struct {
    char* resource;
    char* productId;
    char* productVersion;
    char* clientName;
    ZetaSdk_StorageConfig* storageConfig;
    ZetaSdk_TpmConfig* tpmConfig;
    ZetaSdk_AuthConfig* authConfig;
} ZetaSdk_BuildConfig;

typedef struct {
    void* zetaSdkClient;
} ZetaSdk_Client;

typedef struct {
    void* zetaHttpClient;
} ZetaSdk_HttpClient;

typedef struct {
    char* key;
    char* value;
} ZetaSdk_HttpHeader;

typedef struct {
    char* url;
    char* body;
    ZetaSdk_HttpHeader* headers;
    int headersCount;
} ZetaSdk_HttpRequest;

typedef struct {
    int status;
    char* body;
    ZetaSdk_HttpHeader* headers;
    int headersCount;
    char* error;
} ZetaSdk_HttpResponse;


#endif
